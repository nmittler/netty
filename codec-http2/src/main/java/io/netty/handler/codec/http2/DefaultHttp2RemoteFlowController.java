/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.netty.handler.codec.http2;

import static io.netty.handler.codec.http2.Http2CodecUtil.CONNECTION_STREAM_ID;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_WINDOW_SIZE;
import static io.netty.handler.codec.http2.Http2Error.FLOW_CONTROL_ERROR;
import static io.netty.handler.codec.http2.Http2Error.INTERNAL_ERROR;
import static io.netty.handler.codec.http2.Http2Exception.streamError;
import static io.netty.util.internal.ObjectUtil.checkNotNull;
import static java.lang.Math.max;
import static java.lang.Math.min;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Connection.StreamVisitor;
import io.netty.handler.codec.http2.Http2Stream.State;
import io.netty.util.internal.Iterators;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;

/**
 * Basic implementation of {@link Http2RemoteFlowController}.
 */
public class DefaultHttp2RemoteFlowController implements Http2RemoteFlowController {
    private static final StreamVisitor WRITE_ALLOCATED_BYTES = new StreamVisitor() {
        @Override
        public boolean visit(Http2Stream stream) {
            state(stream).writeAllocatedBytes();
            return true;
        }
    };
    private final Http2Connection connection;
    private int initialWindowSize = DEFAULT_WINDOW_SIZE;
    private ChannelHandlerContext ctx;
    private boolean needFlush;

    public DefaultHttp2RemoteFlowController(Http2Connection connection) {
        this.connection = checkNotNull(connection, "connection");

        // Add a flow state for the connection.
        connection.connectionStream().setProperty(FlowState.class,
                new FlowState(connection.connectionStream(), initialWindowSize));

        // Register for notification of new streams.
        connection.addListener(new Http2ConnectionAdapter() {
            @Override
            public void onStreamAdded(Http2Stream stream) {
                // Just add a new flow state to the stream.
                stream.setProperty(FlowState.class, new FlowState(stream, 0));
            }

            @Override
            public void onStreamActive(Http2Stream stream) {
                // Need to be sure the stream's initial window is adjusted for SETTINGS
                // frames which may have been exchanged while it was in IDLE
                state(stream).window(initialWindowSize);
            }

            @Override
            public void onStreamClosed(Http2Stream stream) {
                // Any pending frames can never be written, cancel and
                // write errors for any pending frames.
                state(stream).cancel();
            }

            @Override
            public void onStreamHalfClosed(Http2Stream stream) {
                if (State.HALF_CLOSED_LOCAL.equals(stream.state())) {
                    /**
                     * When this method is called there should not be any
                     * pending frames left if the API is used correctly. However,
                     * it is possible that a erroneous application can sneak
                     * in a frame even after having already written a frame with the
                     * END_STREAM flag set, as the stream state might not transition
                     * immediately to HALF_CLOSED_LOCAL / CLOSED due to flow control
                     * delaying the write.
                     *
                     * This is to cancel any such illegal writes.
                     */
                     state(stream).cancel();
                }
            }

            @Override
            public void onPriorityTreeParentChanged(Http2Stream stream, Http2Stream oldParent) {
                Http2Stream parent = stream.parent();
                if (parent != null) {
                    int delta = state(stream).streamableBytesForTree();
                    if (delta != 0) {
                        state(parent).incrementStreamableBytesForTree(delta);
                    }
                }
            }

            @Override
            public void onPriorityTreeParentChanging(Http2Stream stream, Http2Stream newParent) {
                Http2Stream parent = stream.parent();
                if (parent != null) {
                    int delta = -state(stream).streamableBytesForTree();
                    if (delta != 0) {
                        state(parent).incrementStreamableBytesForTree(delta);
                    }
                }
            }
        });
    }

    @Override
    public void initialWindowSize(int newWindowSize) throws Http2Exception {
        if (newWindowSize < 0) {
            throw new IllegalArgumentException("Invalid initial window size: " + newWindowSize);
        }

        final int delta = newWindowSize - initialWindowSize;
        initialWindowSize = newWindowSize;
        connection.forEachActiveStream(new StreamVisitor() {
            @Override
            public boolean visit(Http2Stream stream) throws Http2Exception {
                // Verify that the maximum value is not exceeded by this change.
                state(stream).incrementStreamWindow(delta);
                return true;
            }
        });

        if (delta > 0) {
            // The window size increased, send any pending frames for all streams.
            writePendingBytes();
        }
    }

    @Override
    public int initialWindowSize() {
        return initialWindowSize;
    }

    @Override
    public int windowSize(Http2Stream stream) {
        return state(stream).window();
    }

    @Override
    public void incrementWindowSize(ChannelHandlerContext ctx, Http2Stream stream, int delta) throws Http2Exception {
        if (stream.id() == CONNECTION_STREAM_ID) {
            // Update the connection window and write any pending frames for all streams.
            connectionState().incrementStreamWindow(delta);
            writePendingBytes();
        } else {
            // Update the stream window and write any pending frames for the stream.
            FlowState state = state(stream);
            state.incrementStreamWindow(delta);
            state.writeBytes(state.writableWindow());
            flush();
        }
    }

    @Override
    public void sendFlowControlled(ChannelHandlerContext ctx, Http2Stream stream, FlowControlled frame) {
        checkNotNull(ctx, "ctx");
        checkNotNull(frame, "frame");
        if (this.ctx != null && this.ctx != ctx) {
            throw new IllegalArgumentException("Writing data from multiple ChannelHandlerContexts is not supported");
        }
        // Save the context. We'll use this later when we write pending bytes.
        this.ctx = ctx;
        final FlowState state;
        try {
            state = state(stream);
            state.enqueueFrame(frame);
        } catch (Throwable t) {
            frame.error(t);
            return;
        }
        state.writeBytes(state.writableWindow());
        try {
            flush();
        } catch (Throwable t) {
            frame.error(t);
        }
    }

    /**
     * For testing purposes only. Exposes the number of streamable bytes for the tree rooted at
     * the given stream.
     */
    int streamableBytesForTree(Http2Stream stream) {
        return state(stream).streamableBytesForTree();
    }

    private static FlowState state(Http2Stream stream) {
        checkNotNull(stream, "stream");
        return stream.getProperty(FlowState.class);
    }

    private FlowState connectionState() {
        return state(connection.connectionStream());
    }

    /**
     * Returns the flow control window for the entire connection.
     */
    private int connectionWindow() {
        return connectionState().window();
    }

    /**
     * Flushes the {@link ChannelHandlerContext} if we've received any data frames.
     */
    private void flush() {
        if (needFlush) {
            ctx.flush();
            needFlush = false;
        }
    }

    /**
     * Writes as many pending bytes as possible, according to stream priority.
     */
    private void writePendingBytes() throws Http2Exception {
        Http2Stream connectionStream = connection.connectionStream();
        int connectionWindow = state(connectionStream).window();

        if (connectionWindow > 0) {
            // Allocate the bytes for the connection window to the streams, but do not write.
            allocateBytesForTree(connectionStream, connectionWindow);

            // Now write all of the allocated bytes.
            connection.forEachActiveStream(WRITE_ALLOCATED_BYTES);
            flush();
        }
    }

    /**
     * This will allocate bytes by stream weight and priority for the entire tree rooted at {@code parent}, but does not
     * write any bytes. The connection window is generally distributed amongst siblings according to their weight,
     * however we need to ensure that the entire connection window is used (assuming streams have >= connection window
     * bytes to send) and we may need some sort of rounding to accomplish this.
     *
     * @param parent The parent of the tree.
     * @param connectionWindow The connection window this is available for use at this point in the tree.
     * @return An object summarizing the write and allocation results.
     */
    private int allocateBytesForTree(Http2Stream parent, int connectionWindow) {
        FlowState state = state(parent);
        if (state.streamableBytesForTree() <= 0) {
            return 0;
        }
        int bytesAllocated = 0;
        // If the number of streamable bytes for this tree will fit in the connection window
        // then there is no need to prioritize the bytes...everyone sends what they have
        if (state.streamableBytesForTree() <= connectionWindow) {
            for (Http2Stream child : parent.children()) {
                state = state(child);
                int bytesForChild = state.streamableBytes();

                if (bytesForChild > 0 || state.hasFrame()) {
                    state.allocate(bytesForChild);
                    bytesAllocated += bytesForChild;
                    connectionWindow -= bytesForChild;
                }
                int childBytesAllocated = allocateBytesForTree(child, connectionWindow);
                bytesAllocated += childBytesAllocated;
                connectionWindow -= childBytesAllocated;
            }
            return bytesAllocated;
        }

        // This is the priority algorithm which will divide the available bytes based
        // upon stream weight relative to its peers
        ChildCache childCache = new ChildCache(parent);
        int totalWeight = parent.totalChildWeights();
        while (childCache.nextIterationSize() > 0) {
            int nextTotalWeight = 0;
            int nextConnectionWindow = connectionWindow;

            for (Http2Stream child : childCache) {
                if (nextConnectionWindow <= 0) {
                    // The connection window has collapsed for this iteration, nothing to do.
                    break;
                }

                state = state(child);
                int weight = child.weight();
                double weightRatio = weight / (double) totalWeight;

                // In order to make progress toward the connection window due to possible rounding errors, we make sure
                // that each stream (with data to send) is given at least 1 byte toward the connection window.
                int connectionWindowChunk = max(1, (int) (connectionWindow * weightRatio));
                int bytesForTree = min(nextConnectionWindow, connectionWindowChunk);
                int bytesForChild = min(state.streamableBytes(), bytesForTree);

                if (bytesForChild > 0) {
                    state.allocate(bytesForChild);
                    bytesAllocated += bytesForChild;
                    nextConnectionWindow -= bytesForChild;
                    bytesForTree -= bytesForChild;
                    // If this subtree still wants to send then re-insert into children list and re-consider for next
                    // iteration. This is needed because we don't yet know if all the peers will be able to use
                    // all of their "fair share" of the connection window, and if they don't use it then we should
                    // divide their unused shared up for the peers who still want to send.
                    if (nextConnectionWindow > 0 && state.streamableBytesForTree() > 0) {
                        childCache.addToNextIteration(child);
                        nextTotalWeight += weight;
                    }
                }

                if (bytesForTree > 0) {
                    int childBytesAllocated = allocateBytesForTree(child, bytesForTree);
                    bytesAllocated += childBytesAllocated;
                    nextConnectionWindow -= childBytesAllocated;
                }
            }
            connectionWindow = nextConnectionWindow;
            totalWeight = nextTotalWeight;
        }

        return bytesAllocated;
    }

    /**
     * Utility class used by the priority algorithm to manage multiple iterations over the children of a given stream.
     * This class will try to avoid copying the {@link Collection} returned by the parent {@link
     * Http2Stream#children()}} by first iterating over the collection directly. As streams are found that require
     * additional processing, they will be added to the next iteration buffer via {@link
     * #addToNextIteration(Http2Stream)}. The next call to {@link #iterator()} will return an {@link Iterator} that will
     * iterate only over those streams which were added back to the cache.
     */
    private final class ChildCache implements Iterable<Http2Stream> {
        final Collection<? extends Http2Stream> children;
        int nextTail;
        Http2Stream[] childArray = null;

        /**
         * Creates the cache and initializes it to iterate over all children of the given parent in the next
         * iteration.
         */
        ChildCache(Http2Stream parent) {
            this.children = parent.children();
            nextTail = children.size();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Iterator<Http2Stream> iterator() {
            try {
                if (nextTail == 0) {
                    // Nothing to iterate over.
                    return Collections.emptyIterator();
                }

                if (childArray != null) {
                    // We've allocated the child array, iterate over it up to the specified tail position.
                    return Iterators.newIterator(childArray, 0, nextTail);
                }

                if (nextTail != children.size()) {
                    // We should never get here.
                    throw new IllegalStateException(
                            String.format("nextTail (%d) does not match num children (%d)", nextTail, children.size()));
                }

                // On the first iteration we use the children collection directly. Future iterations
                // will use the childArray.
                return (Iterator<Http2Stream>) children.iterator();
            } finally {
                // Re-initialize the next tail.
                nextTail = 0;
            }
        }

        /**
         * Adds the given child to the collection iterated over by the next call to {@link #iterator()}.
         */
        void addToNextIteration(Http2Stream child) {
            if (childArray == null) {
                // Initial size is 1/4 the number of children.
                int initialSize = min(max(4, children.size() / 4), children.size());
                childArray = new Http2Stream[initialSize];
            } else if (nextTail >= childArray.length) {
                // Grow the array by a factor of 2.
                childArray = Arrays.copyOf(childArray, min(children.size(), childArray.length * 2));
            }
            childArray[nextTail++] = child;
        }

        /**
         * Returns the size of the collection to be iterated over by the next call to {@link #iterator()}.
         */
        int nextIterationSize() {
            return nextTail;
        }
    }

    /**
     * The outbound flow control state for a single stream.
     */
    private final class FlowState {
        private final Deque<FlowControlled> pendingWriteQueue;
        private final Http2Stream stream;
        private int window;
        private int pendingBytes;
        private int streamableBytesForTree;
        private int allocated;
        // Set to true while a frame is being written, false otherwise.
        private boolean writing;
        // Set to true if cancel() was called.
        private boolean cancelled;

        FlowState(Http2Stream stream, int initialWindowSize) {
            this.stream = stream;
            window(initialWindowSize);
            pendingWriteQueue = new ArrayDeque<FlowControlled>(2);
        }

        int window() {
            return window;
        }

        void window(int initialWindowSize) {
            window = initialWindowSize;
        }

        /**
         * Increment the number of bytes allocated to this stream by the priority algorithm
         */
        void allocate(int bytes) {
            allocated += bytes;
            // Also artificially reduce the streamable bytes for this tree to give the appearance
            // that the data has been written. This will be restored before the allocated bytes are
            // actually written.
            incrementStreamableBytesForTree(-bytes);
        }

        /**
         * Write bytes allocated bytes for this stream.
         */
        void writeAllocatedBytes() {
            int numBytes = allocated;

            // Restore the number of streamable bytes to this branch.
            incrementStreamableBytesForTree(allocated);
            resetAllocated();

            // Perform the write.
            writeBytes(numBytes);
        }

        /**
         * Reset the number of bytes that have been allocated to this stream by the priority algorithm.
         */
        void resetAllocated() {
            allocated = 0;
        }

        /**
         * Increments the flow control window for this stream by the given delta and returns the new value.
         */
        int incrementStreamWindow(int delta) throws Http2Exception {
            if (delta > 0 && Integer.MAX_VALUE - delta < window) {
                throw streamError(stream.id(), FLOW_CONTROL_ERROR,
                        "Window size overflow for stream: %d", stream.id());
            }
            int previouslyStreamable = streamableBytes();
            window += delta;

            // Update this branch of the priority tree if the streamable bytes have changed for this node.
            int streamableDelta = streamableBytes() - previouslyStreamable;
            if (streamableDelta != 0) {
                incrementStreamableBytesForTree(streamableDelta);
            }
            return window;
        }

        /**
         * Returns the maximum writable window (minimum of the stream and connection windows).
         */
        int writableWindow() {
            return min(window, connectionWindow());
        }

        /**
         * Returns the number of pending bytes for this node that will fit within the
         * {@link #window}. This is used for the priority algorithm to determine the aggregate
         * number of bytes that can be written at each node. Each node only takes into account its
         * stream window so that when a change occurs to the connection window, these values need
         * not change (i.e. no tree traversal is required).
         */
        int streamableBytes() {
            return max(0, min(pendingBytes - allocated, window));
        }

        int streamableBytesForTree() {
            return streamableBytesForTree;
        }

        /**
         * Adds the {@code frame} to the pending queue and increments the pending
         * byte count.
         */
        void enqueueFrame(FlowControlled frame) {
            incrementPendingBytes(frame.size());
            pendingWriteQueue.offer(frame);
        }

        /**
         * Indicates whether or not there are frames in the pending queue.
         */
        boolean hasFrame() {
            return !pendingWriteQueue.isEmpty();
        }

        /**
         * Returns the the head of the pending queue, or {@code null} if empty.
         */
        FlowControlled peek() {
            return pendingWriteQueue.peek();
        }

        /**
         * Clears the pending queue and writes errors for each remaining frame.
         */
        void cancel() {
            cancel(null);
        }

        /**
         * Clears the pending queue and writes errors for each remaining frame.
         *
         * @param cause the {@link Throwable} that caused this method to be invoked.
         */
        void cancel(Throwable cause) {
            cancelled = true;
            // Ensure that the queue can't be modified while
            // we are writing.
            if (writing) {
                return;
            }
            for (;;) {
                FlowControlled frame = pendingWriteQueue.poll();
                if (frame == null) {
                    break;
                }
                writeError(frame, streamError(stream.id(), INTERNAL_ERROR, cause,
                                              "Stream closed before write could take place"));
            }
        }

        /**
         * Writes up to the number of bytes from the pending queue. May write less if limited by the writable window, by
         * the number of pending writes available, or because a frame does not support splitting on arbitrary
         * boundaries.
         */
        int writeBytes(int bytes) {
            int bytesAttempted = 0;
            while (hasFrame()) {
                int maxBytes = min(bytes - bytesAttempted, writableWindow());
                bytesAttempted += write(peek(), maxBytes);
                if (bytes - bytesAttempted <= 0 && !isNextFrameEmpty()) {
                    // The frame had data and all of it was written.
                    break;
                }
            }
            return bytesAttempted;
        }

        /**
         * @return {@code true} if there is a next frame and its size is zero.
         */
        private boolean isNextFrameEmpty() {
            return hasFrame() && peek().size() == 0;
        }

        /**
         * Writes the frame and decrements the stream and connection window sizes. If the frame is in the pending
         * queue, the written bytes are removed from this branch of the priority tree.
         * <p>
         * Note: this does not flush the {@link ChannelHandlerContext}.
         * </p>
         */
        int write(FlowControlled frame, int allowedBytes) {
            int before = frame.size();
            int writtenBytes = 0;
            // In case an exception is thrown we want to
            // remember it and pass it to cancel(Throwable).
            Throwable cause = null;
            try {
                assert !writing;

                // Write the portion of the frame.
                writing = true;
                needFlush |= frame.write(max(0, allowedBytes));
                if (!cancelled && frame.size() == 0) {
                    // This frame has been fully written, remove this frame
                    // and notify it. Since we remove this frame
                    // first, we're guaranteed that its error method will not
                    // be called when we call cancel.
                    pendingWriteQueue.remove();
                    frame.writeComplete();
                }
            } catch (Throwable t) {
                // Mark the state as cancelled, we'll clear the pending queue
                // via cancel() below.
                cancelled = true;
                cause = t;
            } finally {
                writing = false;
                // Make sure we always decrement the flow control windows
                // by the bytes written.
                writtenBytes = before - frame.size();
                decrementFlowControlWindow(writtenBytes);
                decrementPendingBytes(writtenBytes);
                // If a cancellation occurred while writing, call cancel again to
                // clear and error all of the pending writes.
                if (cancelled) {
                    cancel(cause);
                }
            }
            return writtenBytes;
        }

        /**
         * Recursively increments the streamable bytes for this branch in the priority tree starting at the current
         * node.
         */
        void incrementStreamableBytesForTree(int numBytes) {
            streamableBytesForTree += numBytes;
            if (!stream.isRoot()) {
                state(stream.parent()).incrementStreamableBytesForTree(numBytes);
            }
        }

        /**
         * Increments the number of pending bytes for this node. If there was any change to the number of bytes that
         * fit into the stream window, then {@link #incrementStreamableBytesForTree} is called to recursively update
         * this branch of the priority tree.
         */
        void incrementPendingBytes(int numBytes) {
            int previouslyStreamable = streamableBytes();
            pendingBytes += numBytes;

            int delta = streamableBytes() - previouslyStreamable;
            if (delta != 0) {
                incrementStreamableBytesForTree(delta);
            }
        }

        /**
         * If this frame is in the pending queue, decrements the number of pending bytes for the stream.
         */
        void decrementPendingBytes(int bytes) {
            incrementPendingBytes(-bytes);
        }

        /**
         * Decrement the per stream and connection flow control window by {@code bytes}.
         */
        void decrementFlowControlWindow(int bytes) {
            try {
                int negativeBytes = -bytes;
                connectionState().incrementStreamWindow(negativeBytes);
                incrementStreamWindow(negativeBytes);
            } catch (Http2Exception e) {
                // Should never get here since we're decrementing.
                throw new IllegalStateException("Invalid window state when writing frame: " + e.getMessage(), e);
            }
        }

        /**
         * Discards this {@link FlowControlled}, writing an error. If this frame is in the pending queue,
         * the unwritten bytes are removed from this branch of the priority tree.
         */
        void writeError(FlowControlled frame, Http2Exception cause) {
            decrementPendingBytes(frame.size());
            frame.error(cause);
        }
    }
}
