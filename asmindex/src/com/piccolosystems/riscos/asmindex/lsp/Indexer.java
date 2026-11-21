package com.piccolosystems.riscos.asmindex.lsp;

import com.piccolosystems.riscos.asmindex.run.Assembler;
import com.piccolosystems.riscos.asmindex.run.EntryPoint;
import com.piccolosystems.riscos.asmindex.run.Settings;
import com.piccolosystems.riscos.asmindex.source.SourceFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Runs entry points on a background thread and publishes each new
 * {@link Model}. Until a run finishes, the previous results are served.
 *
 * Unsaved editor text reaches the engine through the shared source cache:
 * {@link #setText} replaces a file's entry with the editor's text.
 */
final class Indexer {
    /** How long the text must be still before a re-run starts. */
    static final long DEBOUNCE_MS = 300;
    /** Publish partial results at most this often during a long batch. */
    static final long PUBLISH_MS = 1000;

    private final Settings settings;
    private final Map<Path, SourceFile> cache = new ConcurrentHashMap<>();
    private final EntryIndex.Interner interner = new EntryIndex.Interner();
    private final Consumer<Update> listener;
    private final Consumer<String> log;

    private volatile Model model = Model.EMPTY;
    private final Set<EntryPoint> pending = new LinkedHashSet<>();
    /** Files changed since the current batch started. */
    private final Set<String> changedFiles = new LinkedHashSet<>();
    private long lastChange;
    private long generation;
    private boolean stopped;
    private boolean idle = true;
    private final Thread thread;

    /** A new snapshot, and the entries that changed in it. */
    static final class Update {
        final Model model;
        final Collection<EntryIndex> changed;
        final boolean complete;      // nothing left pending
        Update(Model m, Collection<EntryIndex> c, boolean complete) { model = m; changed = c; this.complete = complete; }
    }

    Indexer(Settings settings, Consumer<Update> listener, Consumer<String> log) {
        this.settings = settings;
        this.listener = listener;
        this.log = log;
        thread = new Thread(this::loop, "asmindex-indexer");
        thread.setDaemon(true);
        thread.start();
    }

    Model model() { return model; }

    /** A file's text as the engine sees it: the editor's if open, else the disk's. Null if unreadable. */
    SourceFile source(String file) {
        Path key = Path.of(file);
        SourceFile f = cache.get(key);
        if (f != null) return f;
        try {
            f = SourceFile.read(key);
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
        SourceFile prev = cache.putIfAbsent(key, f);
        return prev != null ? prev : f;
    }

    /** Whether anything is queued or running. */
    synchronized boolean busy() { return !(idle && pending.isEmpty()); }

    Settings settings() { return settings; }

    /** The source cache, with unsaved editor text, for running an entry point outside the index. */
    Map<Path, SourceFile> cache() { return cache; }

    /** Queues every entry point. */
    synchronized void indexAll() {
        pending.addAll(settings.entries.keySet());
        generation++;
        notifyAll();
    }

    /** Uses {@code text} for {@code file} (null: go back to the file on disk), and re-runs what reads it. */
    void setText(Path file, String text) {
        Path key = file.toAbsolutePath().normalize();
        if (text == null) cache.remove(key);
        else cache.put(key, SourceFile.fromText(key, text));
        changed(key.toString());
    }

    /** Queues every entry point that reads {@code file}, or whose entry file it is. */
    synchronized void changed(String file) {
        Model m = model;
        for (EntryIndex e : m.readers(file)) pending.add(e.entry);
        for (EntryPoint e : settings.entries.keySet())
            if (e.file.toAbsolutePath().normalize().toString().equals(file)) pending.add(e);
        changedFiles.add(file);
        lastChange = System.currentTimeMillis();
        generation++;
        notifyAll();
    }

    synchronized void stop() {
        stopped = true;
        notifyAll();
    }

    /** Waits until nothing is pending or running; for tests. */
    synchronized boolean awaitIdle(long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!(idle && pending.isEmpty())) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) return false;
            wait(left);
        }
        return true;
    }

    private void loop() {
        for (;;) {
            List<EntryPoint> batch;
            long gen;
            synchronized (this) {
                try {
                    for (;;) {
                        if (stopped) return;
                        if (!pending.isEmpty()) {
                            long wait = lastChange + DEBOUNCE_MS - System.currentTimeMillis();
                            if (wait <= 0) break;
                            wait(wait);
                        } else {
                            idle = true;
                            notifyAll();
                            wait();
                        }
                    }
                } catch (InterruptedException e) {
                    return;
                }
                idle = false;
                batch = new ArrayList<>(pending);
                pending.clear();
                changedFiles.clear();
                gen = generation;
            }
            // Quickest first, so the file being edited gets results soon.
            Model before = model;
            batch.sort(Comparator.comparingLong(e -> {
                EntryIndex x = before.entries.get(e);
                return x == null ? 0 : x.millis;
            }));
            Map<EntryPoint, EntryIndex> done = new LinkedHashMap<>();
            long lastPublish = System.currentTimeMillis();
            int i = 0;
            for (; i < batch.size(); i++) {
                synchronized (this) {
                    if (stopped) return;
                    if (generation != gen) break;   // text changed: requeue the rest and start again
                }
                EntryPoint e = batch.get(i);
                try {
                    long t0 = System.nanoTime();
                    Assembler a = new Assembler(e, settings.resolver(e), cache);
                    a.run();
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    done.put(e, new EntryIndex(e, settings.entries.get(e), a, ms, interner));
                } catch (Exception | StackOverflowError x) {
                    log.accept("indexing " + e.file + " failed: " + x);
                }
                if (System.currentTimeMillis() - lastPublish >= PUBLISH_MS && !done.isEmpty()) {
                    publish(done, false);
                    done = new LinkedHashMap<>();
                    lastPublish = System.currentTimeMillis();
                }
            }
            boolean complete;
            synchronized (this) {
                for (int k = i; k < batch.size(); k++) pending.add(batch.get(k));
                // An entry that ran, or was running, when a file it reads changed
                // is stale: the model didn't list it as a reader yet if this was
                // its first run, so changed() may not have queued it.
                if (generation != gen)
                    for (EntryIndex x : done.values())
                        for (String f : changedFiles)
                            if (x.files.contains(f)) { pending.add(x.entry); break; }
                complete = pending.isEmpty();
            }
            publish(done, complete);
        }
    }

    private void publish(Map<EntryPoint, EntryIndex> done, boolean complete) {
        Model m = done.isEmpty() ? model : model.with(done);
        model = m;
        try {
            listener.accept(new Update(m, done.values(), complete));
        } catch (RuntimeException x) {
            log.accept("publishing results failed: " + x);
        }
    }
}
