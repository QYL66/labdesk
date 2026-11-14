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

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class LabEquipmentToolsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LabBookingService bookings = new LabBookingService(jdbc,
            Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"), ZoneOffset.UTC));
    private final LabEquipmentTools tools = new LabEquipmentTools(bookings);

    @Test
    void exposesWriteToolForConfirmationAndReadOnlyQueries() {
        assertThat(tools.labBookEquipmentTool().tool().annotations().readOnlyHint()).isFalse();
        assertThat(tools.labAvailabilityTool().tool().annotations().readOnlyHint()).isTrue();
        assertThat(tools.labMyBookingsTool().tool().annotations().readOnlyHint()).isTrue();
    }

    @Test
    void rejectsUserIdSuppliedInToolArguments() {
        var arguments = Map.<String, Object>of("userId", "someone-else");
        var write = new CallToolRequest("lab_book_equipment", arguments, Map.of());
        var read = new CallToolRequest("lab_list_my_bookings", arguments, Map.of());
        assertThat(tools.labBookEquipmentTool().callHandler().apply(null, write).isError()).isTrue();
        assertThat(tools.labMyBookingsTool().callHandler().apply(null, read).isError()).isTrue();
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsNonHourlyOrOutOfHoursSlotsAndPastAvailability() {
        LocalDate day = LocalDate.of(2026, 11, 14);
        for (LocalTime invalid : new LocalTime[]{LocalTime.of(8, 0), LocalTime.of(10, 30), LocalTime.of(18, 0)}) {
            assertThatThrownBy(() -> bookings.book("user-1", "SCOPE-01", day, invalid))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("整点");
        }
        assertThatThrownBy(() -> bookings.availability(day.minusDays(2), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("今天");
        verifyNoInteractions(jdbc);
    }
}
