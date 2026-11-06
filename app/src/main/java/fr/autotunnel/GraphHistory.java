package io.github.syldegil.autotunnel;

import java.io.*;
import java.util.*;

/** Persistent bounded undo/redo plus an explicit restore checkpoint. All operations are immutable. */
public final class GraphHistory {
    public final GraphModel current, checkpoint;
    public final long revision;
    public final List<GraphModel> undo, redo;
    private static final int LIMIT = 12;
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public GraphHistory(GraphModel initial) { this(initial, initial, 0, Collections.emptyList(), Collections.emptyList()); }
    private GraphHistory(GraphModel current, GraphModel checkpoint, long revision, List<GraphModel> undo, List<GraphModel> redo) {
        if (revision < 0 || undo.size() > LIMIT || redo.size() > LIMIT) throw new IllegalArgumentException("Historique invalide");
        this.current = current; this.checkpoint = checkpoint; this.revision = revision;
        this.undo = Collections.unmodifiableList(new ArrayList<>(undo)); this.redo = Collections.unmodifiableList(new ArrayList<>(redo));
        for (GraphModel g : all()) if (g.profile.scope != current.profile.scope) throw new IllegalArgumentException("Parcours incohérent");
    }
    private List<GraphModel> all() { List<GraphModel> all = new ArrayList<>(undo); all.addAll(redo); all.add(checkpoint); return all; }
    private static List<GraphModel> push(List<GraphModel> stack, GraphModel value) { List<GraphModel> out = new ArrayList<>(stack); out.add(value); if (out.size() > LIMIT) out.remove(0); return out; }
    public GraphHistory change(GraphModel next) { return new GraphHistory(next, checkpoint, revision + 1, push(undo, current), Collections.emptyList()); }
    public GraphHistory undo() {
        if (undo.isEmpty()) return this; List<GraphModel> stack = new ArrayList<>(undo); GraphModel next = stack.remove(stack.size() - 1);
        return new GraphHistory(next, checkpoint, revision + 1, stack, push(redo, current));
    }
    public GraphHistory redo() {
        if (redo.isEmpty()) return this; List<GraphModel> stack = new ArrayList<>(redo); GraphModel next = stack.remove(stack.size() - 1);
        return new GraphHistory(next, checkpoint, revision + 1, push(undo, current), stack);
    }
    public GraphHistory checkpoint() { return new GraphHistory(current, current, revision + 1, undo, redo); }
    public GraphHistory restore() { return change(checkpoint); }
    public byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x41544832); out.writeInt(1); out.writeLong(revision); write(out, current); write(out, checkpoint);
        out.writeInt(undo.size()); for (GraphModel g : undo) write(out, g);
        out.writeInt(redo.size()); for (GraphModel g : redo) write(out, g);
        out.flush(); if (bytes.size() > MAX_BYTES) throw new IOException("Historique trop volumineux"); return bytes.toByteArray();
    }
    private static void write(DataOutputStream out, GraphModel g) throws IOException { byte[] bytes = GraphCodec.encode(g); out.writeInt(bytes.length); out.write(bytes); }
    private static GraphModel read(DataInputStream in) throws IOException { byte[] bytes = new byte[GraphCodec.count(in, GraphCodec.MAX_BYTES)]; in.readFully(bytes); return GraphCodec.decode(bytes); }
    public static GraphHistory decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IOException("Historique trop volumineux");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0x41544832 || in.readInt() != 1) throw new IOException("Version inconnue");
        long revision = in.readLong(); GraphModel current = read(in), checkpoint = read(in); List<GraphModel> undo = new ArrayList<>(), redo = new ArrayList<>();
        int count = GraphCodec.count(in, LIMIT); for (int i = 0; i < count; i++) undo.add(read(in));
        count = GraphCodec.count(in, LIMIT); for (int i = 0; i < count; i++) redo.add(read(in));
        if (in.available() != 0) throw new IOException("Données supplémentaires");
        try { return new GraphHistory(current, checkpoint, revision, undo, redo); } catch (IllegalArgumentException e) { throw new IOException("Historique invalide", e); }
    }
}
