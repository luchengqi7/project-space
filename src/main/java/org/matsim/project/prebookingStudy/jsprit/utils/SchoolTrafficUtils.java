package org.matsim.project.prebookingStudy.jsprit.utils;

public class SchoolTrafficUtils {
    public static double identifySchoolStartTime (String activityType){
        switch (activityType) {
            case "educ_primary":
                return 27900.;
            case "educ_secondary":
                return 27000.;
            case "educ_tertiary":
                return 28800.;
            case "educ_unknown":
                return 27900.;
            default:
                throw new RuntimeException("Activity Type is not one of the following types: 'educ_primary', 'educ_secondary', 'educ_tertiary', 'educ_unknown'");
        }
    }
}
