/*
 * Copyright 2013-2014, ApiFest project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * @author Apostol Terziev
 */
package com.apifest.oauth20;

import com.apifest.oauth20.persistence.DBManager;
import com.apifest.oauth20.persistence.hazelcast.HazelcastDBManager;
import com.apifest.oauth20.persistence.mongodb.MongoDBManager;
import com.apifest.oauth20.persistence.redis.RedisDBManager;

import java.util.concurrent.locks.ReentrantLock;

public class DBManagerFactory {

    private static final ReentrantLock lock = new ReentrantLock();
    protected static volatile DBManager dbManager;

    public static DBManager getInstance() {
        lock.lock();
        try {
            if (dbManager == null) {
                OAuthServerContext ctx = OAuthServer.getContext();
                if ("redis".equalsIgnoreCase(ctx.getDatabaseType())) {
                    dbManager = new RedisDBManager(ctx.getRedisMaster(), ctx.getRedisSentinels());
                } else if ("mongodb".equalsIgnoreCase(ctx.getDatabaseType())) {
                    dbManager = new MongoDBManager(ctx.getMongoDBUri());
                } else {
                    dbManager = new HazelcastDBManager(ctx.getHazelcastClusterName(), ctx.getHazelcastPassword(),
                            ctx.getHost(), ctx.getHazelcastClusterMembers(), ctx.useEmbeddedHazelcast());
                }
            }
            return dbManager;
        } finally {
            lock.unlock();
        }
    }

    public static void init() {
        // that will instantiate a connection to the storage
        getInstance();
    }
}
