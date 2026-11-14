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

package io.github.qyl66.labdesk.mcp.lab;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

final class LabBookingService {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private final JdbcTemplate jdbc;
    private final Clock clock;

    LabBookingService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    String availability(LocalDate date, String equipmentCode) {
        if (date.isBefore(LocalDate.now(clock.withZone(BEIJING)))) {
            throw new IllegalArgumentException("请选择今天或之后的日期");
        }
        List<Equipment> equipment = equipmentCode == null
                ? jdbc.query("SELECT code, name, room FROM labdesk_equipment ORDER BY code",
                        (row, index) -> new Equipment(row.getString(1), row.getString(2), row.getString(3)))
                : List.of(findEquipment(equipmentCode));
        OffsetDateTime now = OffsetDateTime.now(clock.withZone(BEIJING));
        return date + "（北京时间，每段一小时）\n" + equipment.stream()
                .map(item -> {
                    Set<Instant> busy = jdbc.query("""
                            SELECT starts_at FROM labdesk_booking
                            WHERE equipment_code = ? AND starts_at >= ? AND starts_at < ?
                            """, (row, index) -> row.getObject(1, OffsetDateTime.class).toInstant(),
                            item.code(), at(date, 9), at(date, 18)).stream().collect(Collectors.toSet());
                    String slots = IntStream.rangeClosed(9, 17).mapToObj(hour -> at(date, hour))
                            .filter(start -> start.isAfter(now) && !busy.contains(start.toInstant()))
                            .map(start -> start.format(TIME)).collect(Collectors.joining("、"));
                    return item.label() + " · " + (slots.isEmpty() ? "无空闲时段" : slots);
                }).collect(Collectors.joining("\n"));
    }

    String book(String userId, String equipmentCode, LocalDate date, LocalTime time) {
        if (time.getHour() < 9 || time.getHour() > 17 || time.getMinute() != 0 || time.getSecond() != 0) {
            throw new IllegalArgumentException("请选择 09:00 至 17:00 之间的整点，每次预约一小时");
        }
        Equipment equipment = findEquipment(equipmentCode);
        OffsetDateTime start = at(date, time.getHour());
        boolean future = start.isAfter(OffsetDateTime.now(clock.withZone(BEIJING)));
        if (future) {
            jdbc.update("""
                    INSERT INTO labdesk_booking (equipment_code, starts_at, user_id) VALUES (?, ?, ?)
                    ON CONFLICT (equipment_code, starts_at) DO NOTHING
                    """, equipment.code(), start, userId);
        }
        Optional<Reservation> reserved = jdbc.query("""
                SELECT id, user_id FROM labdesk_booking WHERE equipment_code = ? AND starts_at = ?
                """, (row, index) -> new Reservation(row.getLong(1), row.getString(2)), equipment.code(), start)
                .stream().findFirst();
        if (reserved.filter(booking -> booking.userId().equals(userId)).isPresent()) {
            return "预约编号 LD-" + reserved.orElseThrow().id() + " · " + equipment.label() + " · " + formatSlot(start);
        }
        throw new IllegalArgumentException(future ? "该时段已被预约，请重新查询空闲时段" : "请选择尚未开始的时段");
    }

    String myBookings(String userId) {
        List<String> bookings = jdbc.query("""
                SELECT b.id, b.starts_at, e.code, e.name, e.room
                FROM labdesk_booking b JOIN labdesk_equipment e ON e.code = b.equipment_code
                WHERE b.user_id = ? ORDER BY b.starts_at DESC, b.id DESC LIMIT 30
                """, (row, index) -> "LD-" + row.getLong(1) + " · "
                + new Equipment(row.getString(3), row.getString(4), row.getString(5)).label() + " · "
                + formatSlot(row.getObject(2, OffsetDateTime.class)), userId);
        return bookings.isEmpty() ? "你目前没有预约记录。" : "最近的预约（北京时间）\n" + String.join("\n", bookings);
    }

    private Equipment findEquipment(String code) {
        return jdbc.query("SELECT code, name, room FROM labdesk_equipment WHERE code = ?",
                        (row, index) -> new Equipment(row.getString(1), row.getString(2), row.getString(3)),
                        code.strip().toUpperCase(Locale.ROOT)).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("设备编号不存在，请先查询设备列表"));
    }

    private static OffsetDateTime at(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(BEIJING).toOffsetDateTime();
    }

    private static String formatSlot(OffsetDateTime start) {
        OffsetDateTime local = start.atZoneSameInstant(BEIJING).toOffsetDateTime();
        return local.toLocalDate() + " " + local.format(TIME) + "–" + local.plusHours(1).format(TIME);
    }

    private record Equipment(String code, String name, String room) {
        String label() { return code + " " + name + "（" + room + "）"; }
    }

    private record Reservation(long id, String userId) { }
}
