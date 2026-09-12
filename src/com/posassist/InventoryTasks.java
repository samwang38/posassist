package com.posassist;

import java.util.concurrent.*;
import javax.swing.SwingUtilities;

/** One bounded queue per login. A timed-out native call never causes a second worker to spawn. */
final class InventoryTasks implements AutoCloseable {
    interface Completion<T> { void finish(T value, Exception error); }
    private static final String DEFAULT_TIMEOUT_MESSAGE = "庫存查詢逾時，已勾選商品已保留；請稍後重試";
    private final ThreadPoolExecutor worker;
    private final ScheduledExecutorService clock;
    private boolean closed;
    private long generation;
    private final long timeoutMs;
    private final String timeoutMessage;
    InventoryTasks() { this(45000); }
    InventoryTasks(long timeoutMs) { this(timeoutMs, DEFAULT_TIMEOUT_MESSAGE, "PosAssist-Inventory"); }
    /** Same queue semantics for any single-flight DB work; only the timeout wording and thread name differ. */
    InventoryTasks(long timeoutMs, String timeoutMessage, final String threadName) {
        this.timeoutMs = timeoutMs;
        this.timeoutMessage = timeoutMessage;
        ThreadFactory factory = new ThreadFactory() {
            public Thread newThread(Runnable r) { Thread t = new Thread(r,threadName); t.setDaemon(true); return t; }
        };
        worker = new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(1),factory);
        clock = Executors.newSingleThreadScheduledExecutor(factory);
    }
    synchronized void invalidate() { generation++; worker.getQueue().clear(); }
    <T> void run(final Callable<T> task, final Completion<T> completion) {
        final long ticket;
        synchronized (this) {
            if (closed) return;
            ticket = ++generation; worker.getQueue().clear();
        }
        final java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean();
        final Runnable expire = new Runnable() {
            public void run() { deliver(ticket,delivered,completion,null,new Exception(timeoutMessage)); }
        };
        final ScheduledFuture<?> timeout = clock.schedule(expire,timeoutMs,TimeUnit.MILLISECONDS);
        worker.execute(new Runnable() {
            public void run() {
                synchronized (InventoryTasks.this) { if (closed || generation != ticket || delivered.get()) { timeout.cancel(false); return; } }
                T value = null; Exception error = null;
                try { value = task.call(); } catch (Exception e) { error = e; }
                catch (LinkageError e) { error = new Exception("EPB 庫存模組不相容"); }
                timeout.cancel(false);
                deliver(ticket,delivered,completion,value,error);
            }
        });
    }
    private <T> void deliver(final long ticket, java.util.concurrent.atomic.AtomicBoolean delivered,
                            final Completion<T> completion, final T value, final Exception error) {
        if (!delivered.compareAndSet(false,true)) return;
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                synchronized (InventoryTasks.this) { if (closed || generation != ticket) return; }
                completion.finish(value,error);
            }
        });
    }
    public synchronized void close() {
        closed = true; generation++; worker.getQueue().clear(); worker.shutdownNow(); clock.shutdownNow();
    }
}
