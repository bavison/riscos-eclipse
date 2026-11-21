package com.piccolosystems.riscos.lsp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.eclipse.lsp4e.LanguageClientImpl;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;

/** LSP4E's client, plus the indexer's own notifications. */
public class RiscOSLanguageClient extends LanguageClientImpl {

    /** The clients of servers (normally one running), oldest first; held weakly. */
    private static final List<java.lang.ref.WeakReference<RiscOSLanguageClient>> LIVE = new ArrayList<>();

    /** The clients still about, oldest first. */
    private static List<RiscOSLanguageClient> clients() {
        synchronized (LIVE) {
            List<RiscOSLanguageClient> out = new ArrayList<>();
            LIVE.removeIf(r -> r.get() == null);
            for (var r : LIVE) {
                RiscOSLanguageClient c = r.get();
                if (c != null) out.add(c);
            }
            return out;
        }
    }

    public RiscOSLanguageClient() {
        synchronized (LIVE) {
            LIVE.add(new java.lang.ref.WeakReference<>(this));
        }
    }

    /** The server's commands for C editors: where assembler defines a name, and where it uses it. */
    public static final String ASM_DEFINITIONS = "riscos.asmDefinitions", ASM_REFERENCES = "riscos.asmReferences";

    /**
     * A running server, or null: the newest client's. LSP4E starts a new
     * server (and client) when it needs one again after stopping the last; the
     * older clients keep their stopped servers.
     */
    private static LanguageServer running() {
        List<RiscOSLanguageClient> newestFirst = clients();
        Collections.reverse(newestFirst);
        for (RiscOSLanguageClient c : newestFirst) {
            LanguageServer ls = c.getLanguageServer();
            if (ls != null) return ls;
        }
        return null;
    }

    /** Whether the language server is running. */
    public static boolean isRunning() {
        return running() != null;
    }

    /**
     * Starts the language server, if it isn't running, by connecting it to an
     * assembler file in the workspace, as opening one would; waits up to
     * {@code millis} for it.
     */
    public static boolean start(long millis) {
        if (running() != null) return true;
        org.eclipse.core.resources.IFile[] found = new org.eclipse.core.resources.IFile[1];
        try {
            org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().accept(proxy -> {
                if (found[0] != null) return false;
                if (proxy.getType() == org.eclipse.core.resources.IResource.FILE && proxy.getName().endsWith(".s"))
                    found[0] = (org.eclipse.core.resources.IFile) proxy.requestResource();
                return found[0] == null;
            }, 0);
        } catch (org.eclipse.core.runtime.CoreException e) {
            return false;
        }
        if (found[0] == null) return false;
        org.eclipse.lsp4e.LanguageServiceAccessor.getLSWrappers(found[0], null);
        long end = System.currentTimeMillis() + millis;
        while (running() == null && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return running() != null;
    }

    /**
     * Runs one of the server's commands for a C file ({@link #ASM_DEFINITIONS}
     * or {@link #ASM_REFERENCES}) and returns the locations it gives; an empty
     * list if the server isn't running or doesn't answer within {@code millis}.
     */
    public static List<org.eclipse.lsp4j.Location> asmLocations(String command, String name, java.net.URI cFile, long millis) {
        return locations(command, List.of(name, cFile.toString()), millis);
    }

    /**
     * A file's symbols, as its outline shows them: {@code textDocument/documentSymbol}
     * for assembler, and the server's {@code riscos.basicSymbols} for BASIC, which
     * works whether or not the file is open. Starts the server if need be; none
     * if it can't be started.
     */
    static java.util.concurrent.CompletableFuture<List<org.eclipse.lsp4j.DocumentSymbol>> symbols(java.net.URI uri, boolean basic) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (running() == null) start(30_000);
            LanguageServer ls = running();
            if (ls == null) return List.of();
            try {
                com.google.gson.Gson gson = new com.google.gson.Gson();
                if (basic) {
                    Object r = ls.getWorkspaceService().executeCommand(new org.eclipse.lsp4j.ExecuteCommandParams(
                            BASIC_SYMBOLS, List.of(uri.toString()))).get(60, java.util.concurrent.TimeUnit.SECONDS);
                    if (r == null) return List.of();
                    com.google.gson.JsonElement json = r instanceof com.google.gson.JsonElement j ? j : gson.toJsonTree(r);
                    return List.of(gson.fromJson(json, org.eclipse.lsp4j.DocumentSymbol[].class));
                }
                var r = ls.getTextDocumentService().documentSymbol(new org.eclipse.lsp4j.DocumentSymbolParams(
                        new org.eclipse.lsp4j.TextDocumentIdentifier(uri.toString()))).get(120, java.util.concurrent.TimeUnit.SECONDS);
                List<org.eclipse.lsp4j.DocumentSymbol> out = new ArrayList<>();
                if (r != null) for (var e : r) if (e.isRight()) out.add(e.getRight());
                return out;
            } catch (Exception e) {
                return List.of();
            }
        });
    }

    static final String BASIC_SYMBOLS = "riscos.basicSymbols";

    /** {@code riscos.assignments}: where the assembler variable at a position is assigned. */
    static final String ASSIGNMENTS = "riscos.assignments";

    /** The locations a server command gives, waiting at most {@code millis}; none if it doesn't answer. */
    static List<org.eclipse.lsp4j.Location> locations(String command, List<Object> args, long millis) {
        LanguageServer ls = running();
        if (ls == null) return List.of();
        try {
            Object r = ls.getWorkspaceService().executeCommand(new org.eclipse.lsp4j.ExecuteCommandParams(command,
                    args)).get(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (r == null) return List.of();
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonElement json = r instanceof com.google.gson.JsonElement j ? j : gson.toJsonTree(r);
            return List.of(gson.fromJson(json, org.eclipse.lsp4j.Location[].class));
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Tells running servers to index another configuration, or the same one
     * again ({@code reload}) after its settings were rewritten. A server
     * started later reads the preference instead.
     */
    static void settingsChanged(String dir, boolean reload) {
        for (RiscOSLanguageClient c : clients()) {
            try {
                LanguageServer ls = c.getLanguageServer();
                if (ls == null) continue;
                ls.getWorkspaceService().didChangeConfiguration(new DidChangeConfigurationParams(
                        Map.of("riscos", Map.of("settings", Preferences.settingsDirs(dir), "reload", reload))));
            } catch (RuntimeException e) {
                // That server has stopped; the next one reads the preference.
                Platform.getLog(RiscOSLanguageClient.class).info("Couldn't tell the language server: " + e);
            }
        }
    }

    /** Parameters of {@code riscos/inactiveRegions}. */
    public static class InactiveRegionsParams {
        public VersionedTextDocumentIdentifier textDocument;
        /** Lines no entry point of the configuration assembles. */
        public List<Range> regions;
        /** Macro body lines skipped in every expansion: not dead code, so shown only if enabled. */
        public List<Range> skippedInEveryExpansion;
    }

    /** Parameters of {@code riscos/status}. */
    public static class StatusParams {
        public String state;          // "indexing" or "idle"
        public int entryPoints;
    }

    @JsonNotification("riscos/inactiveRegions")
    public void inactiveRegions(InactiveRegionsParams params) {
        InactiveRegions.show(params);
    }

    /** Parameters of {@code riscos/sourceFiles}. */
    public static class SourceFilesParams {
        /** Every file the assembler read, by objasm's name for it and its real path. */
        public List<String> files;
    }

    @JsonNotification("riscos/sourceFiles")
    public void sourceFiles(SourceFilesParams params) {
        if (params != null) SourceFileTypes.update(params.files);
    }

    @JsonNotification("riscos/status")
    public void status(StatusParams params) {
        // Progress is reported through window/workDoneProgress; nothing else to do.
    }
}
