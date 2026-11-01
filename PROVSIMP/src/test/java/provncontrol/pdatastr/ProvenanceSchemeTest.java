package provncontrol.pdatastr;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Exercises the provenance argumentation scheme shipped with PROVSIMP.
 * The scheme text is the original embedded catalogue, including the
 * critical question on a missing provenance element.
 */
public class ProvenanceSchemeTest {

    @Test
    public void provenanceCriticalQuestionsAreLoaded() {
        Schemes schemes = new Schemes();
        assertTrue(String.valueOf(schemes.getCQs()), schemes.getCQs().contains("CQPV4"));
        assertTrue(schemes.getCQs().contains("CQPV2"));
    }
}
