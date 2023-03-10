package com.example.emos.workflow.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import com.example.emos.workflow.bo.Approval;
import com.example.emos.workflow.config.quartz.MeetingWorkflowJob;
import com.example.emos.workflow.config.quartz.QuartzUtil;
import com.example.emos.workflow.service.LeaveService;
import com.example.emos.workflow.service.MeetingService;
import com.example.emos.workflow.service.ReimService;
import com.example.emos.workflow.service.WorkflowService;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.activiti.engine.HistoryService;
import org.activiti.engine.ProcessEngine;
import org.activiti.engine.RuntimeService;
import org.activiti.engine.TaskService;
import org.activiti.engine.history.HistoricProcessInstance;
import org.activiti.engine.history.HistoricProcessInstanceQuery;
import org.activiti.engine.history.HistoricTaskInstance;
import org.activiti.engine.history.HistoricTaskInstanceQuery;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.Task;
import org.activiti.engine.task.TaskQuery;
import org.drools.core.base.RuleNameStartsWithAgendaFilter;
import org.kie.api.runtime.KieSession;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class WorkflowServiceImpl implements WorkflowService {
    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private TaskService taskService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private KieSession kieSession;

    @Autowired
    private MeetingService meetingService;

    @Autowired
    private LeaveService leaveService;

    @Autowired
    private ReimService reimService;

    @Autowired
    private QuartzUtil quartzUtil;

    @Override
    public String startMeetingProcess(HashMap param) {
        String instanceId = runtimeService.startProcessInstanceByKey("meeting", param).getProcessInstanceId();
        String uuid = param.get("uuid").toString();
        String date = param.get("date").toString();
        String start = param.get("start").toString();

        JobDetail jobDetail = JobBuilder.newJob(MeetingWorkflowJob.class).build();
        Map dataMap = jobDetail.getJobDataMap();
        dataMap.put("uuid", uuid);
        dataMap.put("instanceId", instanceId);

        Date executeDate = DateUtil.parse(date + " " + start, "yyyy-MM-dd HH:mm:ss");
        quartzUtil.addJob(jobDetail, uuid, "??????????????????", executeDate);

        return instanceId;
    }

    @Override
    public String startLeaveProcess(HashMap param) {
        String instanceId = runtimeService.startProcessInstanceByKey("leave", param).getProcessInstanceId(); //???????????????
        return instanceId;
    }

    @Override
    public String startReimProcess(HashMap param) {
        String instanceId = runtimeService.startProcessInstanceByKey("reim", param).getProcessInstanceId(); //???????????????
        return instanceId;
    }


    @Override
    public void approvalTask(HashMap param) {
        String taskId = MapUtil.getStr(param, "taskId");
        String approval = MapUtil.getStr(param, "approval");
        taskService.setVariableLocal(taskId, "result", approval);
        taskService.complete(taskId);
    }

    @Override
    public void archiveTask(HashMap param) {
        String taskId = MapUtil.getStr(param, "taskId");
        int userId = MapUtil.getInt(param, "userId");
        JSONArray files = (JSONArray) param.get("files");
        taskService.setVariable(taskId, "files", files);
        taskService.setVariable(taskId, "filing", false);
        taskService.setOwner(taskId, userId + "");
        taskService.setAssignee(taskId, userId + "");
        taskService.complete(taskId);
    }


    @Override
    public boolean searchProcessStatus(String instanceId) {
        ProcessInstance instance = runtimeService.createProcessInstanceQuery().processInstanceId(instanceId).singleResult();
        return instance == null;
    }

    @Override
    public void deleteProcessById(String uuid, String instanceId, String type, String reason) {
        long count = runtimeService.createProcessInstanceQuery().processInstanceId(instanceId).count();
        if (count > 0) {
            runtimeService.deleteProcessInstance(instanceId, reason);
        }
        count = historyService.createHistoricProcessInstanceQuery().processInstanceId(instanceId).count();
        if (count > 0) {
            historyService.deleteHistoricProcessInstance(instanceId);
        }
        if (type.equals("????????????")) {
            quartzUtil.deleteJob(uuid, "?????????????????????");
            quartzUtil.deleteJob(uuid, "?????????????????????");
            quartzUtil.deleteJob(uuid, "??????????????????");
            quartzUtil.deleteJob(uuid, "???????????????ID?????????");
        }


    }

    @Override
    public ArrayList searchProcessUsers(String instanceId) {
        List<HistoricTaskInstance> taskList = historyService.createHistoricTaskInstanceQuery().processInstanceId(instanceId).finished().list();
        ArrayList<String> list = new ArrayList<>();
        taskList.forEach(one -> {
            list.add(one.getAssignee());
        });
        return list;
    }

    @Override
    public HashMap searchTaskByPage(HashMap param) {
        ArrayList<Approval> list = new ArrayList();
        int userId = (Integer) param.get("userId");
        JSONArray role = (JSONArray) param.get("role");
        int start = (Integer) param.get("start");
        int length = (Integer) param.get("length");
        String status = (String) param.get("status");
        String creatorName = MapUtil.getStr(param, "creatorName");
        String type = MapUtil.getStr(param, "type");
        String instanceId = MapUtil.getStr(param, "instanceId");
        Long totalCount = 0L;
        List<String> assignee = new ArrayList();
        assignee.add(String.valueOf(userId));
        role.forEach(one -> {
            assignee.add(one.toString());
        });

        if ("?????????".equals(status)) {
            TaskQuery taskQuery = taskService.createTaskQuery()
                    .taskAssigneeIds(assignee)
                    .orderByTaskCreateTime().desc()
                    .includeProcessVariables()
                    .includeTaskLocalVariables();

            if (StrUtil.isNotBlank(creatorName)) {
                taskQuery.processVariableValueEquals("creatorName", creatorName);
            }
            if (StrUtil.isNotBlank(type)) {
                taskQuery.processVariableValueEquals("type", type);
            }
            if (StrUtil.isNotBlank(instanceId)) {
                taskQuery.processInstanceId(instanceId);
            }
            totalCount = taskService.createTaskQuery()
                    .includeProcessVariables()
                    .includeTaskLocalVariables()
                    .taskAssigneeIds(assignee)
                    .count();
            List<Task> taskList = taskQuery.listPage(start, length);
            taskList.forEach(task -> {
                Map map = task.getProcessVariables();
                Approval approval = createApproval(task.getProcessInstanceId(), status, map);
                approval.setTaskId(task.getId());
                list.add(approval);
            });
        } else {
            if ("?????????".equals(status)) {
                HistoricTaskInstanceQuery taskQuery = historyService.createHistoricTaskInstanceQuery()
                        .orderByHistoricTaskInstanceStartTime().desc()
                        .includeTaskLocalVariables().includeProcessVariables()
                        .taskAssigneeIds(assignee).finished().processUnfinished();
                if (StrUtil.isNotBlank(creatorName)) {
                    taskQuery.processVariableValueEquals("creatorName", creatorName);
                }
                if (StrUtil.isNotBlank(type)) {
                    taskQuery.processVariableValueEquals("type", type);
                }
                if (StrUtil.isNotBlank(instanceId)) {
                    taskQuery.processInstanceId(instanceId);
                }
                totalCount = taskService.createTaskQuery()
                        .includeProcessVariables()
                        .includeTaskLocalVariables()
                        .taskAssigneeIds(assignee)
                        .count();
                List<HistoricTaskInstance> taskList = taskQuery.listPage(start, length);
                for (HistoricTaskInstance task : taskList) {
                    Map map = task.getProcessVariables();
                    Approval approval = createApproval(task.getProcessInstanceId(), status, map);
                    approval.setTaskId(task.getId());
                    list.add(approval);
                }
            } else if ("?????????".equals(status)) {
                HistoricTaskInstanceQuery taskQuery = historyService.createHistoricTaskInstanceQuery()
                        .orderByHistoricTaskInstanceStartTime().desc()
                        .includeTaskLocalVariables().includeProcessVariables()
                        .taskAssigneeIds(assignee).finished().processFinished();
                List<HistoricTaskInstance> taskList = taskQuery.listPage(start, length);
                for (HistoricTaskInstance task : taskList) {
                    Map map = task.getProcessVariables();
                    Approval approval = createApproval(task.getProcessInstanceId(), status, map);
                    approval.setTaskId(task.getId());
                    list.add(approval);
                }
            }
        }
        HashMap map = new HashMap();
        map.put("list", list);
        map.put("totalCount", totalCount);
        map.put("pageIndex", start);
        map.put("pageSize", length);
        return map;
    }

    @Override
    public HashMap searchApprovalContent(String instanceId, int userId, String[] role, String type, String status) {
        HashMap map = null;
        List<String> assignee = new ArrayList();
        assignee.add(userId + "");
        Collections.addAll(assignee, role);
        if ("????????????".equals(type)) {
            map = meetingService.searchMeetingByInstanceId(instanceId);
        } else if ("????????????".equals(type)) {
            map = leaveService.searchLeaveByInstanceId(instanceId);
        } else if ("????????????".equals(type)) {
            map = reimService.searchReimByInstanceId(instanceId);
        }
        Map variables;
        if (!"?????????".equals(status)) {
            variables = runtimeService.getVariables(instanceId);
        } else {
            HistoricTaskInstance instance = historyService.createHistoricTaskInstanceQuery()
                    .includeTaskLocalVariables().includeProcessVariables().processInstanceId(instanceId).taskAssigneeIds(assignee).processFinished().list().get(0);
            variables = instance.getProcessVariables();
        }
        if (variables != null && variables.containsKey("files")) {
            ArrayNode files = (ArrayNode) variables.get("files");
            map.put("files", files);
        }

        ProcessInstance instance = runtimeService.createProcessInstanceQuery().processInstanceId(instanceId).singleResult();
        if (instance != null) {
            map.put("result", "");
        } else {
            map.put("result", variables.get("result"));
        }
        return map;
    }


    private Approval createApproval(String processId, String status, Map map) {
        String type = (String) map.get("type");
        String createDate = (String) map.get("createDate");
        boolean filing = (Boolean) map.get("filing");

        Approval approval = new Approval();
        approval.setCreatorName(map.get("creatorName").toString());
        approval.setProcessId(processId);
        approval.setType(type);
        approval.setTitle(map.get("title").toString());
        approval.setStatus(status);
        approval.setCreateDate(createDate);
        approval.setFiling(filing);
        approval.setResult(MapUtil.getStr(map, "result"));
        return approval;
    }
}
