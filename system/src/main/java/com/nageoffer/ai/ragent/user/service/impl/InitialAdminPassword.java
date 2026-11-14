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

package com.nageoffer.ai.ragent.user.service.impl;

import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class InitialAdminPassword implements ApplicationRunner {

    private final UserMapper users;
    private final AccountPasswords passwords;

    @Override
    public void run(ApplicationArguments args) {
        UserDO admin = users.selectOne(Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername, "admin").eq(UserDO::getDeleted, 0));
        if (admin == null || passwords.isEncoded(admin.getPassword())) {
            return;
        }

        String initial = System.getenv("LABDESK_ADMIN_PASSWORD");
        if (initial == null || initial.isBlank()) {
            throw new IllegalStateException("Set LABDESK_ADMIN_PASSWORD to initialize the LabDesk admin account");
        }
        users.update(null, Wrappers.lambdaUpdate(UserDO.class)
                .eq(UserDO::getId, admin.getId())
                .eq(UserDO::getPassword, admin.getPassword())
                .set(UserDO::getPassword, passwords.encode(initial)));
    }
}
