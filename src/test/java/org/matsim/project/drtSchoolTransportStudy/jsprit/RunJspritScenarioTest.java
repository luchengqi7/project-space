package org.matsim.project.drtSchoolTransportStudy.jsprit;

import org.junit.Assert;
import org.junit.Test;

public class RunJspritScenarioTest {

    @Test
    public void testMain() {
        try {
            RunJspritScenario runJspritScenario = new RunJspritScenario();
            String [] args = {
                    "--config", "scenarios/vulkaneifel/school-childrem.config.xml",
                    "--mode", "drt",
                    "--nr-iter", "100",
                    "--stats-output-path", "output_jsprit/school-traffic/euclidian-distance/JspritDefault",
                    "--print-memory-interval", "60",
                    "--max-velocity", "15",
                    "--OF", "JspritDefault",
                    "--run-test"
            } ;
            runJspritScenario.execute(args);
            {
                /*
                running test for jsprit
                 */
                //assertTrue(bestSolution.getUnassignedJobs().contains(job2));
                Assert.assertEquals(2, runJspritScenario.reasonTracker.getMostLikelyReasonCode("10238_school_trip#0"));
                Assert.assertEquals(2, runJspritScenario.reasonTracker.getMostLikelyReasonCode("10211_school_trip#0"));
                Assert.assertEquals(2, runJspritScenario.reasonTracker.getMostLikelyReasonCode("10209_school_trip#0"));
                Assert.assertEquals(2, runJspritScenario.reasonTracker.getMostLikelyReasonCode("10203_school_trip#0"));
                Assert.assertEquals(2, runJspritScenario.reasonTracker.getMostLikelyReasonCode("10204_school_trip#0"));
                Assert.assertEquals(2, runJspritScenario.reasonTracker.getMostLikelyReasonCode("10208_school_trip#0"));
                Assert.assertEquals(2, runJspritScenario.reasonTracker.getMostLikelyReasonCode("10332_school_trip#0"));
                Assert.assertEquals(2, runJspritScenario.reasonTracker.getMostLikelyReasonCode("10052_school_trip#0"));
                Assert.assertEquals(2, runJspritScenario.reasonTracker.getMostLikelyReasonCode("10324_school_trip#0"));
                Assert.assertEquals(2, runJspritScenario.reasonTracker.getMostLikelyReasonCode("10330_school_trip#0"));
                Assert.assertEquals(2, runJspritScenario.reasonTracker.getMostLikelyReasonCode("10201_school_trip#0"));
                Assert.assertEquals(2, runJspritScenario.reasonTracker.getMostLikelyReasonCode("10325_school_trip#0"));
                Assert.assertEquals(2, runJspritScenario.reasonTracker.getMostLikelyReasonCode("10235_school_trip#0"));
                Assert.assertEquals(2, runJspritScenario.reasonTracker.getMostLikelyReasonCode("10210_school_trip#0"));
                Assert.assertEquals(2, runJspritScenario.reasonTracker.getMostLikelyReasonCode("10212_school_trip#0"));
                Assert.assertEquals(2, runJspritScenario.reasonTracker.getMostLikelyReasonCode("10206_school_trip#0"));
            }
        } catch (Exception ee) {
            ee.printStackTrace();
            Assert.fail();
        }
    }
}