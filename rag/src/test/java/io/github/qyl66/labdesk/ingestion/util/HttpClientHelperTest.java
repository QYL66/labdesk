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

package io.github.qyl66.labdesk.ingestion.util;

import io.github.qyl66.labdesk.framework.exception.ServiceException;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpClientHelperTest {

    private final HttpClientHelper client = new HttpClientHelper(new OkHttpClient());

    @Test
    void rejectsNonHttpDocumentsAndEmbeddedCredentials() {
        assertThatThrownBy(() -> client.head("file:///sample.md", null))
                .isInstanceOf(ServiceException.class).hasMessageContaining("HTTP(S)");
        assertThatThrownBy(() -> client.head("https://test-user:test-password@example.invalid/sample.md", null))
                .isInstanceOf(ServiceException.class).hasMessageContaining("HTTP(S)");
    }

    @Test
    void rejectsLocalDocumentAddressesBeforeConnecting() {
        assertThatThrownBy(() -> client.head("http://127.0.0.1/sample.md", null))
                .isInstanceOf(ServiceException.class).hasMessageContaining("公开网络");
    }
}
