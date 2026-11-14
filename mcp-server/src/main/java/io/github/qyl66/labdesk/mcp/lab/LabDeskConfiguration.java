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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Objects;

@Configuration
@Profile("labdesk")
class LabDeskConfiguration {

    @Bean
    LabBookingService labBookingService(JdbcTemplate jdbc) {
        var schema = new ResourceDatabasePopulator(new ClassPathResource("db/labdesk.sql"));
        schema.setSqlScriptEncoding("UTF-8");
        schema.execute(Objects.requireNonNull(jdbc.getDataSource()));
        return new LabBookingService(jdbc, Clock.systemUTC());
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> labDeskMcpAccess(@Value("${labdesk.mcp-token}") String token) {
        if (token.isBlank()) {
            throw new IllegalArgumentException("LabDesk MCP token is required");
        }
        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String supplied = request.getHeader("X-LabDesk-Mcp-Token");
                if (supplied == null || !MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                chain.doFilter(request, response);
            }
        };
        var registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/mcp");
        return registration;
    }
}
