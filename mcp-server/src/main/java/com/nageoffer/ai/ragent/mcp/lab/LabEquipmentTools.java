/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.mcp.lab;

import com.nageoffer.ai.ragent.mcp.config.McpToolAnnotations;
import com.nageoffer.ai.ragent.mcp.executor.McpToolResults;
import com.nageoffer.ai.ragent.mcp.executor.McpToolSchema;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.function.Function;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;

@Slf4j
@Component
@Profile("labdesk")
@RequiredArgsConstructor
class LabEquipmentTools {

    private final LabBookingService bookings;

    @Bean
    SyncToolSpecification labAvailabilityTool() {
        return tool("lab_list_availability", "查询实验室设备在指定日期的空闲时段；可选设备编号，省略则列出全部设备",
                McpToolSchema.object().required(dateField()).optional(equipmentField()), true,
                request -> {
                    Map<String, Object> args = McpToolResults.args(request);
                    return McpToolResults.success(bookings.availability(date(args), optionalText(args, "equipmentCode")));
                });
    }

    @Bean
    SyncToolSpecification labBookEquipmentTool() {
        String name = "lab_book_equipment";
        return tool(name, "为当前登录用户预约设备。先查询空闲；本操作需要用户确认，收到成功结果后才能告知预约编号",
                McpToolSchema.object().required(equipmentField()).required(dateField())
                        .required(string("startTime", "北京时间 HH:mm，09:00 到 17:00 的整点").title("开始时间")), false,
                request -> {
                    String user = McpToolResults.userId(request);
                    if (user == null) { return McpToolResults.identityRequired(name); }
                    Map<String, Object> args = McpToolResults.args(request);
                    String time = requiredText(args, "startTime");
                    if (!time.matches("[0-9]{2}:[0-9]{2}")) {
                        throw new IllegalArgumentException("开始时间格式为 HH:mm");
                    }
                    return McpToolResults.success(bookings.book(user, requiredText(args, "equipmentCode"),
                            date(args), LocalTime.parse(time)));
                });
    }

    @Bean
    SyncToolSpecification labMyBookingsTool() {
        String name = "lab_list_my_bookings";
        return tool(name, "查看当前登录用户最近的设备预约记录", McpToolSchema.object(), true,
                request -> {
                    String user = McpToolResults.userId(request);
                    return user == null ? McpToolResults.identityRequired(name)
                            : McpToolResults.success(bookings.myBookings(user));
                });
    }

    private static SyncToolSpecification tool(String name, String description, McpToolSchema schema,
                                             boolean readOnly, Function<CallToolRequest, CallToolResult> handler) {
        return SyncToolSpecification.builder()
                .tool(Tool.builder().name(name).description(description).inputSchema(schema.build())
                        .annotations(readOnly ? McpToolAnnotations.READ_ONLY : McpToolAnnotations.WRITE).build())
                .callHandler((exchange, request) -> {
                    try {
                        return handler.apply(request);
                    } catch (DateTimeParseException e) {
                        return McpToolResults.error("日期或时间无效，请使用 yyyy-MM-dd 和 HH:mm");
                    } catch (IllegalArgumentException e) {
                        return McpToolResults.error(e.getMessage());
                    } catch (Exception e) {
                        log.error("LabDesk MCP tool failed: {}", name, e);
                        return McpToolResults.failure("设备预约服务", e);
                    }
                }).build();
    }

    private static McpToolSchema.Property dateField() {
        return string("date", "北京时间日期，yyyy-MM-dd").title("预约日期");
    }

    private static McpToolSchema.Property equipmentField() {
        return string("equipmentCode", "设备编号，如 SCOPE-01，应取自设备查询结果").title("设备编号");
    }

    private static LocalDate date(Map<String, Object> args) {
        String date = requiredText(args, "date");
        if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new IllegalArgumentException("日期格式为 yyyy-MM-dd");
        }
        return LocalDate.parse(date);
    }

    private static String optionalText(Map<String, Object> args, String field) {
        return args.containsKey(field) ? requiredText(args, field) : null;
    }

    private static String requiredText(Map<String, Object> args, String field) {
        if (!(args.get(field) instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("缺少有效参数 " + field);
        }
        return value.strip();
    }
}
