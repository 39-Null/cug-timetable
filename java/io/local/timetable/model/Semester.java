package io.local.timetable.model;

import java.io.Serializable;

/** 一个学期的课表档位。 */
public class Semester implements Serializable {
    public long id;
    public String name = "";
    /** 第一周周一，yyyy-MM-dd */
    public String start = "";
    public long created;
    /** 排序号，用于历史课表拖拽排序 */
    public int sort;
    /** 仅用于列表展示，不入库 */
    public int courseCount;
}
