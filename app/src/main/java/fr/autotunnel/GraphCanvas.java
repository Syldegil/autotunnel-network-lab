package io.github.syldegil.autotunnel;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;
import static io.github.syldegil.autotunnel.GraphModel.*;

/** Native interactive world-space canvas. Accessible equivalent actions are supplied by the Activity. */
final class GraphCanvas extends View {
    interface Listener {
        void change(GraphModel next);
        void selection(String id);
        void edit(String id);
        void connect(String from, Port output, String to, Port input);
        void feedback(String text);
    }
    static final float WIDTH = 242, HEIGHT = 174;
    private GraphModel graph;
    private final Listener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestures;
    private String selected, dragNode, portNode;
    private Port port;
    private float lastX, lastY, downX, downY, wireX, wireY;
    private boolean changed, multiTouch;
    private final Map<String, String> observations = new HashMap<>();
    private final Map<String,RoutingProfile.Mode> plannedModes=new HashMap<>();
    private GraphModel gestureStart;
    GraphCanvas(Context context, GraphModel graph, Listener listener) {
        super(context); this.graph = graph; this.listener = listener; density = getResources().getDisplayMetrics().density;
        setBackgroundColor(AppStyle.bg(getContext())); setFocusable(true);
        setContentDescription("Éditeur de graphe. Glisser un nœud pour déplacer ; glisser une sortie vers une entrée compatible ; fond pour déplacer la vue ; pincement pour zoomer. Les mêmes actions sont disponibles dans Objets et Connecter.");
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) { multiTouch = true; dragNode = null; portNode = null; return true; }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                zoomAt(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY()); return true;
            }
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { if (selected != null) listener.edit(selected); return true; }
            @Override public void onLongPress(MotionEvent e) { if (!changed && selected != null && portNode == null) listener.edit(selected); }
        });
    }
    void model(GraphModel value) {
        graph=value;plannedModes.clear();GraphCompiler.Result compiled=GraphCompiler.compile(graph);
        if(compiled.valid())for(Link link:graph.links)if(link.enabled)for(RoutingTopology.Branch branch:compiled.topology.branches)
            if(branch.nodes.contains(link.from)&&branch.nodes.contains(link.to))plannedModes.put(link.id,branch.profile.mode);
        if(selected!=null&&graph.node(selected)==null&&graph.link(selected)==null)selected=null;invalidate();
    }
    void select(String id) { selected = id; listener.selection(id); invalidate(); }
    void observations(Map<String, String> value) { observations.clear(); observations.putAll(value); invalidate(); }
    private float x(float screen) { return (screen / density - graph.panX) / graph.zoom; }
    private float y(float screen) { return (screen / density - graph.panY) / graph.zoom; }
    private boolean visible(Node n) {
        if (graph.view != ViewMode.PHYSICAL) return true;
        return n.kind.upstream() || n.kind == Kind.WIREGUARD || n.kind == Kind.INTERNET || n.kind == Kind.INTERFACE || n.kind == Kind.PHONE_INTERFACE || n.kind == Kind.USB || n.kind == Kind.HOTSPOT || n.kind == Kind.FREEBOX;
    }
    private boolean visible(Link l) {
        if (!visible(graph.node(l.from)) || !visible(graph.node(l.to))) return false;
        return graph.view != ViewMode.PHYSICAL || l.output.type == 1 || l.output.type == 2;
    }
    static PointF point(Node n, Port port) {
        int index = 0; for (Port p : n.kind.ports()) { if (p == port) break; if (p.output == port.output) index++; }
        return new PointF(n.x + (port.output ? WIDTH : 0), n.y + 72 + index * 28);
    }
    private void color(int color) { paint.setColor(color); paint.setStyle(Paint.Style.FILL); paint.setPathEffect(null); }
    private int accent(int type) { return type == 0 ? Color.rgb(74, 200, 225) : type == 1 ? Color.rgb(190, 149, 255) : type == 2 ? Color.rgb(248, 183, 85) : Color.rgb(159, 177, 193); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save(); canvas.scale(density, density); canvas.translate(graph.panX, graph.panY); canvas.scale(graph.zoom, graph.zoom);
        color(AppStyle.color(getContext(),"#dce4ed","#1f2b3a"));
        float left = x(0), top = y(0), right = x(getWidth()), bottom = y(getHeight());
        for (float gx = (float)Math.floor(left / 40) * 40; gx < right; gx += 40)
            for (float gy = (float)Math.floor(top / 40) * 40; gy < bottom; gy += 40) canvas.drawCircle(gx, gy, 1.2f, paint);
        for (Link l : graph.links) if (visible(l)) drawLink(canvas, l);
        for (Node n : graph.nodes) if (visible(n)) drawNode(canvas, n);
        if (portNode != null) {
            PointF a = point(graph.node(portNode), port); PortHit target = portAt(wireX, wireY,port);
            boolean valid = target != null && port.accepts(target.port) && !target.node.id.equals(portNode);
            color(valid ? Color.GREEN : Color.rgb(255, 190, 90)); paint.setStrokeWidth(3); canvas.drawLine(a.x, a.y, wireX, wireY, paint);
            paint.setTextSize(13); canvas.drawText(valid ? "Relâcher pour connecter" : "Viser une entrée de même couleur", wireX + 12, wireY - 16, paint);
        }
        canvas.restore();
        color(AppStyle.muted(getContext())); paint.setTextSize(12 * density);
        canvas.drawText((graph.view == ViewMode.PHYSICAL ? "PHYSIQUE · transport déclaré" : graph.view == ViewMode.LIVE ? "LIVE · mesures locales, trajets non vérifiés" : "LOGIQUE · configuration non appliquée") + " · " + Math.round(graph.zoom * 100) + "%", 12 * density, getHeight() - 12 * density, paint);
    }
    private void drawNode(Canvas canvas, Node n) {
        color(n.id.equals(selected) ? AppStyle.color(getContext(),"#dce9f8","#27465d") : AppStyle.panel(getContext())); canvas.drawRoundRect(n.x, n.y, n.x + WIDTH, n.y + HEIGHT, 12, 12, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(n.id.equals(selected) ? 3 : 1); paint.setColor(n.enabled ? accent(n.kind.upstream() ? 1 : 0) : Color.rgb(101, 113, 131));
        canvas.drawRoundRect(n.x, n.y, n.x + WIDTH, n.y + HEIGHT, 12, 12, paint);
        color(AppStyle.ink(getContext())); paint.setTypeface(Typeface.DEFAULT_BOLD); paint.setTextSize(15); canvas.drawText(shorten(n.label, 27), n.x + 12, n.y + 24, paint);
        paint.setTypeface(Typeface.DEFAULT); paint.setTextSize(11); paint.setColor(AppStyle.muted(getContext()));
        canvas.drawText(n.enabled ? "CONFIGURÉ · jamais appliqué" : "DESCRIPTIF · exclu du moteur", n.x + 12, n.y + 44, paint);
        for (Port p : n.kind.ports()) {
            PointF pos = point(n, p); color(accent(p.type)); canvas.drawCircle(pos.x, pos.y, 7, paint); paint.setTextSize(10);
            paint.setTextAlign(p.output ? Paint.Align.RIGHT : Paint.Align.LEFT); canvas.drawText(p.title, pos.x + (p.output ? -12 : 12), pos.y + 4, paint); paint.setTextAlign(Paint.Align.LEFT);
        }
        color(AppStyle.muted(getContext())); paint.setTextSize(11);
        String state = graph.view == ViewMode.LIVE ? observations.getOrDefault(n.id, "Observation non attribuée") : n.properties.family == null ? "Famille/politique : hériter du profil" : n.properties.family + " · " + n.properties.policy;
        canvas.drawText(shorten(state, 34), n.x + 12, n.y + HEIGHT - 12, paint);
    }
    private void drawLink(Canvas canvas, Link l) {
        PointF a = point(graph.node(l.from), l.output), b = point(graph.node(l.to), l.input);
        color(l.id.equals(selected) ? AppStyle.ink(getContext()) : linkColor(l)); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(l.id.equals(selected) ? 4 : 2);
        if (!l.enabled) paint.setPathEffect(new DashPathEffect(new float[]{8, 6}, 0));
        Path path = curve(a, b); canvas.drawPath(path, paint); paint.setPathEffect(null);
        canvas.drawLine(b.x, b.y, b.x - 9, b.y - 5, paint); canvas.drawLine(b.x, b.y, b.x - 9, b.y + 5, paint);
        paint.setStyle(Paint.Style.FILL); paint.setTextSize(10);
        String label = l.output.type == 1 ? "UDP chiffré extérieur · déclaré" : l.output.type == 2 ? "Transport physique déclaré" : l.output.type == 3 ? "Mesure non attribuée" : ipLabel(l);
        canvas.drawText(label, (a.x + b.x) / 2 - 64, (a.y + b.y) / 2 - 9, paint);
    }
    private int linkColor(Link l) {
        if (l.output.type != 0) return accent(l.output.type);
        String label = ipLabel(l);
        if (label.startsWith("DNS")) return Color.rgb(244, 143, 195);
        if (label.startsWith("LAN")) return Color.rgb(113, 208, 146);
        if (label.startsWith("Blocage")) return Color.rgb(255, 125, 125);
        if (label.startsWith("Direct")) return Color.rgb(248, 183, 85);
        return Color.rgb(100, 177, 255);
    }
    private String ipLabel(Link l) {
        Node from = graph.node(l.from), to = graph.node(l.to);
        if (from.kind == Kind.DNS || to.kind == Kind.DNS || !l.properties.dns.isEmpty()) return "DNS prévu · non attribué";
        if (from.kind == Kind.HOME_LAN || to.kind == Kind.HOME_LAN) return "LAN domestique · prévu";
        if (from.kind == Kind.KILL_SWITCH || to.kind == Kind.KILL_SWITCH) return "Blocage en panne demandé";
        if (plannedModes.get(l.id)==RoutingProfile.Mode.DIRECT || l.properties.policy == GraphModel.Policy.DIRECT || from.properties.policy==GraphModel.Policy.DIRECT || graph.profile.mode == RoutingProfile.Mode.DIRECT) return "Direct non chiffré · prévu";
        return from.kind == Kind.WIREGUARD ? "VPN interne · trajet prévu" : "IP vers VPN · non chiffré ici";
    }
    private Path curve(PointF a, PointF b) { float bend = Math.max(55, Math.abs(b.x - a.x) / 2); Path path = new Path(); path.moveTo(a.x, a.y); path.cubicTo(a.x + bend, a.y, b.x - bend, b.y, b.x, b.y); return path; }
    private static String shorten(String s, int max) { return s.length() <= max ? s : s.substring(0, max - 1) + "…"; }
    private Node nodeAt(float x, float y) {
        for (int i = graph.nodes.size() - 1; i >= 0; i--) { Node n = graph.nodes.get(i); if (visible(n) && x >= n.x && x <= n.x + WIDTH && y >= n.y && y <= n.y + HEIGHT) return n; } return null;
    }
    private static final class PortHit { final Node node; final Port port; PortHit(Node n, Port p) { node = n; port = p; } }
    private PortHit portAt(float x, float y) {
        return portAt(x,y,null);
    }
    private PortHit portAt(float x,float y,Port expected){
        PortHit best=null;double distance=24/graph.zoom;
        for(Node n:graph.nodes)if(visible(n))for(Port p:n.kind.ports())if(expected==null||expected.accepts(p)){
            PointF at=point(n,p);double d=Math.hypot(x-at.x,y-at.y);if(d<distance){distance=d;best=new PortHit(n,p);}
        }return best;
    }
    private Link linkAt(float x, float y) {
        for (Link l : graph.links) if (visible(l)) {
            PointF a = point(graph.node(l.from), l.output), b = point(graph.node(l.to), l.input); PathMeasure measure = new PathMeasure(curve(a, b), false);
            float[] pos = new float[2]; for (int i = 0; i <= 80; i++) { measure.getPosTan(measure.getLength() * i / 80, pos, null); if (Math.hypot(pos[0] - x, pos[1] - y) < 12 / graph.zoom) return l; }
        } return null;
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event); gestures.onTouchEvent(event);
        float wx = x(event.getX()), wy = y(event.getY());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureStart=graph;
                changed = false; multiTouch = false; dragNode = null; portNode = null; downX = lastX = event.getX(); downY = lastY = event.getY();
                PortHit hit = portAt(wx, wy);
                if (hit != null && hit.port.output) { portNode = hit.node.id; port = hit.port; wireX = wx; wireY = wy; select(hit.node.id); listener.feedback("Connexion : viser une entrée compatible ; annuler en relâchant sur le fond."); }
                else { Node n = nodeAt(wx, wy); Link l = n == null ? linkAt(wx, wy) : null; select(n != null ? n.id : l != null ? l.id : null); if (n != null) dragNode = n.id; }
                getParent().requestDisallowInterceptTouchEvent(true); break;
            case MotionEvent.ACTION_MOVE:
                if (scaleDetector.isInProgress() || multiTouch) break;
                if (portNode != null) { wireX = wx; wireY = wy; }
                else if (Math.hypot(event.getX() - downX, event.getY() - downY) > 5 * density) {
                    float dx = (event.getX() - lastX) / density, dy = (event.getY() - lastY) / density;
                    if (dragNode != null) { Node n = graph.node(dragNode); graph = graph.replace(n.move(clamp(n.x + dx / graph.zoom), clamp(n.y + dy / graph.zoom))); }
                    else graph = graph.camera(clamp(graph.panX + dx), clamp(graph.panY + dy), graph.zoom, graph.view);
                    changed = true;
                }
                lastX = event.getX(); lastY = event.getY(); invalidate(); break;
            case MotionEvent.ACTION_UP:
                if (portNode != null) {
                    PortHit target = portAt(wx, wy,port);
                    if (target != null && port.accepts(target.port) && !target.node.id.equals(portNode)) listener.connect(portNode, port, target.node.id, target.port);
                    else listener.feedback("Connexion annulée : entrée absente ou incompatible.");
                } else if (changed) listener.change(graph); else performClick();
                portNode = null; dragNode = null; getParent().requestDisallowInterceptTouchEvent(false); invalidate(); break;
            case MotionEvent.ACTION_CANCEL:
                if(gestureStart!=null)graph=gestureStart;portNode=null;dragNode=null;getParent().requestDisallowInterceptTouchEvent(false);invalidate();break;
            default: break;
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    private float clamp(float n) { return Math.max(-99000, Math.min(99000, n)); }
    private void zoomAt(float factor, float focusX, float focusY) {
        float wx = x(focusX), wy = y(focusY), zoom = Math.max(.2f, Math.min(3f, graph.zoom * factor));
        graph = graph.camera(clamp(focusX / density - wx * zoom), clamp(focusY / density - wy * zoom), zoom, graph.view); changed = true; invalidate();
    }
    void zoom(float factor) { zoomAt(factor, getWidth() / 2f, getHeight() / 2f); listener.change(graph); }
    void pan(float dx, float dy) { listener.change(graph.camera(clamp(graph.panX + dx), clamp(graph.panY + dy), graph.zoom, graph.view)); }
    void fit() {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (Node n : graph.nodes) if (visible(n)) { minX = Math.min(minX, n.x); minY = Math.min(minY, n.y); maxX = Math.max(maxX, n.x + WIDTH); maxY = Math.max(maxY, n.y + HEIGHT); }
        if (minX == Float.MAX_VALUE || getWidth() == 0 || getHeight() == 0) return;
        float width = getWidth() / density, height = getHeight() / density;
        float zoom = Math.max(.2f, Math.min(2f, Math.min((width - 40) / (maxX - minX), (height - 60) / (maxY - minY))));
        listener.change(graph.camera((width - (maxX - minX) * zoom) / 2 - minX * zoom, (height - (maxY - minY) * zoom) / 2 - minY * zoom, zoom, graph.view));
    }
    void focusSelected(){Node n=graph.node(selected);if(n==null){listener.feedback("Sélectionnez un nœud via Objets, puis Zoom sélection.");return;}float z=Math.max(1,graph.zoom);listener.change(graph.camera(getWidth()/density/2-(n.x+WIDTH/2)*z,getHeight()/density/2-(n.y+HEIGHT/2)*z,z,graph.view));}
    float centerX() { return x(getWidth() / 2f) - WIDTH / 2; }
    float centerY() { return y(getHeight() / 2f) - HEIGHT / 2; }
}
