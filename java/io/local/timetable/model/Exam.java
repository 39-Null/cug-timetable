package io.local.timetable.model;

import java.io.Serializable;

/** 考试安排。 */
public class Exam implements Serializable {
    public long id;
    public String course = "";
    /** 形如 2027-01-05 14:00 */
    public String time = "";
    public String place = "";
    public String seat = "";
    public String note = "";

    /** 所属学期 */
    public long semesterId = 0;

    public Exam() {
    }

    public Exam(String course, String time, String place) {
        this.course = course;
        this.time = time;
        this.place = place;
    }
}
