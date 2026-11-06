package io.github.syldegil.autotunnel;

import org.junit.Test;
import static org.junit.Assert.*;

public class GraphEvidenceTest {
    @Test public void timestampsExpireAndClockReversalNeverLooksFresh(){assertTrue(GraphEvidence.fresh(1000,16000));assertFalse(GraphEvidence.fresh(1000,16001));assertFalse(GraphEvidence.fresh(1000,999));assertFalse(GraphEvidence.fresh(0,1000));}
    @Test public void compiledLinkNeverBecomesInstalledOrVerified(){GraphModel g=GraphTemplates.create(4);String evidence=GraphEvidence.link(g,g.link("usb_firewall"));assertTrue(evidence.contains("USB/REQUIRE_TUNNEL"));assertTrue(evidence.contains("INSTALLÉ : non attribué"));assertTrue(evidence.contains("VÉRIFIÉ BOUT EN BOUT : non qualifié"));}
    @Test public void invalidOrDescriptiveLinkDoesNotClaimCalculatedRoute(){GraphModel g=GraphTemplates.create(4);assertTrue(GraphEvidence.link(g,g.link("outer")).contains("lien descriptif"));g=g.remove("wg_freebox");assertTrue(GraphEvidence.link(g,g.link("usb_firewall")).contains("refusé"));}
}
