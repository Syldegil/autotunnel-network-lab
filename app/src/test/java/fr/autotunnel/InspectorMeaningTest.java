package io.github.syldegil.autotunnel;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class InspectorMeaningTest {
    @Test public void missingObservationDoesNotClaimOffline(){assertEquals("Connexion inconnue",InspectorMeaning.connection(false,false,""));assertEquals("Aucun réseau actif",InspectorMeaning.connection(true,false,""));}
    @Test public void transportIsNotReachability(){assertEquals("Connecté en Wi-Fi",InspectorMeaning.connection(true,true,"Wi-Fi"));assertEquals("Accès Internet à vérifier",InspectorMeaning.internet(true,true,false));assertEquals("Internet détecté par Android",InspectorMeaning.internet(true,true,true));}
    @Test public void staleTunnelNeverLooksActive(){assertEquals("État inconnu",InspectorMeaning.tunnel(false,"Interface locale active"));assertEquals("État à vérifier",InspectorMeaning.tunnel(true,"Erreur du backend · état inconnu"));assertEquals("Tunnel arrêté",InspectorMeaning.tunnel(true,"Interface locale arrêtée"));}
    private DiagnosticSequence.Result result(DiagnosticSequence.Status a,DiagnosticSequence.Status b,DiagnosticSequence.Status c){return new DiagnosticSequence.Result(1,"example.com","fixture",Arrays.asList(new DiagnosticSequence.Step("DNS",a,1,""),new DiagnosticSequence.Step("TCP",b,1,""),new DiagnosticSequence.Step("TLS",c,1,"Certificat")));}
    @Test public void successfulTlsDoesNotPromiseHttpOrAllInternet(){DiagnosticSequence.Result r=result(DiagnosticSequence.Status.PASS,DiagnosticSequence.Status.PASS,DiagnosticSequence.Status.PASS);assertEquals("Connexion chiffrée vérifiée",InspectorMeaning.verdict(r));assertTrue(InspectorMeaning.guidance(r).contains("contenu du site n’a pas été testé"));}
    @Test public void timeoutDoesNotClaimHostDead(){DiagnosticSequence.Result r=result(DiagnosticSequence.Status.PASS,DiagnosticSequence.Status.TIMEOUT,DiagnosticSequence.Status.NOT_RUN);assertEquals("La réponse se fait attendre",InspectorMeaning.verdict(r));assertTrue(InspectorMeaning.guidance(r).contains("ne prouve pas"));}
    @Test public void missingAndRunningAreDifferent(){assertEquals("Aucun serveur testé",InspectorMeaning.verdict(null));assertEquals("Vérification en cours…",InspectorMeaning.verdict(result(DiagnosticSequence.Status.RUNNING,DiagnosticSequence.Status.NOT_RUN,DiagnosticSequence.Status.NOT_RUN)));assertEquals("Pas encore testé",InspectorMeaning.stage(2,DiagnosticSequence.Status.NOT_RUN));}
}
