package com.nous.buildkit;

/** Держит не больше одной активной операции над миром. */
public final class JobManager {

    private final BuildKitPlugin plugin;
    private TickJob current;

    public JobManager(BuildKitPlugin plugin) {
        this.plugin = plugin;
    }

    public TickJob current() {
        return current;
    }

    public boolean busy() {
        return current != null;
    }

    public void submit(TickJob job) {
        if (current != null) {
            throw new CommandError("уже выполняется операция '" + current.label()
                    + "' — дождитесь её окончания");
        }
        current = job;
        try {
            job.start();
        } catch (RuntimeException e) {
            current = null;
            throw e;
        }
    }

    void finished(TickJob job) {
        if (current == job) {
            current = null;
        }
    }

    public void cancelAll() {
        TickJob job = current;
        current = null;
        if (job != null) {
            job.cancel();
        }
    }
}
