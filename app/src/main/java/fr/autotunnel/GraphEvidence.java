package io.github.syldegil.autotunnel;

/** Link proof levels never derive from global counters, configuration or a handshake alone. */
public final class GraphEvidence {
    private GraphEvidence(){}
    public static boolean fresh(long at,long now){return at>0&&now>=at&&now-at<=15000;}
    public static String link(GraphModel graph,GraphModel.Link link){
        GraphCompiler.Result result=GraphCompiler.compile(graph);StringBuilder out=new StringBuilder("CONFIGURÉ : "+link.from+" → "+link.to+"\n");
        out.append("CALCULÉ : ");if(!link.enabled)out.append("lien descriptif, aucune opération");else if(!result.valid())out.append("refusé : ").append(result.report());else{
            for(RoutingTopology.Branch b:result.topology.branches)if(b.nodes.contains(link.from)&&b.nodes.contains(link.to))out.append(b.profile.scope).append("/").append(b.profile.mode).append(" ");
        }
        return out.append("\nINSTALLÉ : non attribué à ce lien\nPARTIELLEMENT OBSERVÉ : aucune mesure par lien ; compteurs interfaces séparés\nVÉRIFIÉ BOUT EN BOUT : non qualifié\nAucune animation de trafic déduite des compteurs globaux.").toString();
    }
}
