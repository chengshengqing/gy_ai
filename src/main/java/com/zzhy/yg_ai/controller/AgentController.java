package com.zzhy.yg_ai.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/agent")
public class AgentController {

    @GetMapping("/agent_generate")
    public Map<String, String> generate(@RequestParam("msg") String msg, @RequestParam("sessionId")  String sessionId){
        return Map.of("call", "ok");
    }
}
