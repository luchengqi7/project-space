package org.matsim.project.prebookingStudy.jsprit.utils;

import org.matsim.core.gbl.Gbl;

public class SchoolTrafficUtils {

    public enum SchoolStartTimeScheme {Disabled, DefaultSchoolStartTime, Eight, SchoolType}

    public static double identifySchoolStartTime (SchoolStartTimeScheme schoolStartTimeScheme, String activityType){

        switch (schoolStartTimeScheme) {
            case DefaultSchoolStartTime:
                return schoolStartTimeIsEight();
            case Eight:
                return schoolStartTimeIsEight();
            case SchoolType:
                return identifySchoolStartTimeBasedOnSchoolType (activityType);
            default:
                throw new RuntimeException(Gbl.NOT_IMPLEMENTED);
        }
    }

    public static double identifySchoolStartTimeBasedOnSchoolType (String activityType){
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
    public static double schoolStartTimeIsEight (){
        return 28800.;
    }
}
