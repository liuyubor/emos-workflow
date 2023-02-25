package com.example.emos.workflow.bpmn;

import cn.hutool.core.date.DateField;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import com.example.emos.workflow.config.quartz.MeetingRoomJob;
import com.example.emos.workflow.config.quartz.MeetingStatusJob;
import com.example.emos.workflow.config.quartz.QuartzUtil;
import com.example.emos.workflow.service.MeetingService;
import com.example.emos.workflow.service.impl.MeetingServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.activiti.engine.delegate.DelegateExecution;
import org.activiti.engine.delegate.JavaDelegate;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component("notifyMeetingService")
public class NotifyMeetingService implements JavaDelegate {
    @Autowired
    private QuartzUtil quartzUtil;

    @Autowired
    private MeetingService meetingService;

    @Override
    public void execute(DelegateExecution delegateExecution) {
        Map map = delegateExecution.getVariables();
        String uuid = MapUtil.getStr(map, "uuid");
        String url = MapUtil.getStr(map, "url");
        String result = MapUtil.getStr(map, "result");

        HashMap data = meetingService.searchMeetingByUUID(uuid);
        String title = MapUtil.getStr(data, "title");
        String date = MapUtil.getStr(data, "date");
        String start = MapUtil.getStr(data, "start");
        String end = MapUtil.getStr(data, "end");
        if (result.equals("同意")) {
            meetingService.updateMeetingStatus(new HashMap() {{
                put("uuid", uuid);
                put("status", 3);
            }});

            String meetingType = delegateExecution.getVariable("meetingType", String.class);
            if (meetingType.equals("线上会议")) {
                JobDetail jobDetail = JobBuilder.newJob(MeetingRoomJob.class).build();
                Map param = jobDetail.getJobDataMap();
                param.put("uuid", uuid);
                Date expire = DateUtil.parse(date + " " + end, "yyyy-MM-dd HH:mm");
                param.put("expire", expire);
                Date executeDate = DateUtil.parse(date + " " + start, "yyyy-MM-dd HH:mm").offset(DateField.MINUTE, -15);
                quartzUtil.addJob(jobDetail, uuid, "创建会议室ID任务组", executeDate);
            }
            quartzUtil.deleteJob(uuid, "会议工作流组");            
        }
        JobDetail jobDetail = JobBuilder.newJob(MeetingStatusJob.class).build();
        map = jobDetail.getJobDataMap();
        map.put("uuid", uuid);
        map.put("status", 4);
        map.put("flag", "start");
        Date executeDate = DateUtil.parse(date + " " + start, "yyyy-MM-dd HH:mm");
        quartzUtil.addJob(jobDetail, uuid, "会议任务组1", executeDate);

        jobDetail = JobBuilder.newJob(MeetingStatusJob.class).build();
        map = jobDetail.getJobDataMap();
        map.put("uuid", uuid);
        map.put("status", 5);
        executeDate = DateUtil.parse(date + " " + end, "yyyy-MM-dd HH:mm");
        quartzUtil.addJob(jobDetail, uuid, "会议任务组2", executeDate);
        
        JSONObject json = new JSONObject();
        String processId = delegateExecution.getProcessInstanceId();
        json.set("processId", processId);
        
    }
}
