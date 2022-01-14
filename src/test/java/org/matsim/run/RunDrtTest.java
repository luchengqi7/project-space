package org.matsim.run;

import org.junit.Rule;
import org.junit.Test;
import org.matsim.testcases.MatsimTestUtils;

public class RunDrtTest {
    @Rule
    public MatsimTestUtils utils = new MatsimTestUtils();

    @Test
    public final void testDrtDoorToDoor() {
        //TODO here is a dummy test. Add a real test later
        System.out.println("Starting dummy test");
        double x = 1;
        double y = 2;
        double z = x + y;
        assert x + y == z : "some thing is wrong!!!";
    }
}
