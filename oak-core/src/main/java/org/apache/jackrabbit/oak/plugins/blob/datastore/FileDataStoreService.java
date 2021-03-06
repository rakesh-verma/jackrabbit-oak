/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.jackrabbit.oak.plugins.blob.datastore;

import com.google.common.base.Preconditions;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.jackrabbit.core.data.CachingFDS;
import org.apache.jackrabbit.core.data.DataStore;
import org.apache.jackrabbit.oak.commons.PropertiesUtil;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import java.util.Map;
import java.util.Properties;

@Component(policy = ConfigurationPolicy.REQUIRE, name = FileDataStoreService.NAME)
public class FileDataStoreService extends AbstractDataStoreService {
    public static final String NAME = "org.apache.jackrabbit.oak.plugins.blob.datastore.FileDataStore";

    public static final String CACHE_PATH = "cachePath";
    public static final String CACHE_SIZE = "cacheSize";
    public static final String FS_BACKEND_PATH = "fsBackendPath";
    public static final String PATH = "path";

    private Logger log = LoggerFactory.getLogger(getClass());

    @Override
    protected DataStore createDataStore(ComponentContext context, Map<String, Object> config) {

        long cacheSize = PropertiesUtil.toLong(config.get(CACHE_SIZE), 0L);
        // return CachingFDS when cacheSize > 0
        if (cacheSize > 0) {
            String fsBackendPath = PropertiesUtil.toString(config.get(PATH), null);
            Preconditions.checkNotNull(fsBackendPath, "Cannot create " +
                    "FileDataStoreService with caching. [{path}] property not configured.");
            CachingFDS dataStore = new CachingFDS();
            // Disabling asyncUpload by default
            dataStore.setAsyncUploadLimit(PropertiesUtil.toInteger(config.get("asyncUploadLimit"), 0));
            config.remove(PATH);
            config.remove(CACHE_SIZE);
            config.put(FS_BACKEND_PATH, fsBackendPath);
            config.put("cacheSize", cacheSize);
            String cachePath = PropertiesUtil.toString(config.get(CACHE_PATH), null);
            if (cachePath != null) {
                config.remove(CACHE_PATH);
                config.put(PATH, cachePath);
            }
            Properties properties = new Properties();
            properties.putAll(config);
            dataStore.setProperties(properties);
            log.info("CachingFDS initialized with properties " + properties);
            return dataStore;
        } else {
            log.info("OakFileDataStore initialized");
            return new OakFileDataStore();
        }
    }

    @Override
    protected String[] getDescription() {
        return new String[]{"type=filesystem"};
    }
}
