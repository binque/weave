/*
 * Copyright 2012-2013 Continuuity,Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.continuuity.weave.discovery;

import com.google.common.base.Charsets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;
import java.net.InetSocketAddress;

/**
 * Helper class to serialize and deserialize {@link Discoverable}.
 */
final class DiscoverableAdapter {

  private static final Gson GSON =
    new GsonBuilder().registerTypeAdapter(Discoverable.class, new DiscoverableCodec()).create();

  /**
   * Helper function for encoding an instance of {@link Discoverable} into array of bytes.
   * @param discoverable An instance of {@link Discoverable}
   * @return array of bytes representing an instance of <code>discoverable</code>
   */
  static byte[] encode(Discoverable discoverable) {
    return GSON.toJson(discoverable, Discoverable.class).getBytes(Charsets.UTF_8);
  }

  /**
   * Helper function for decoding array of bytes into a {@link Discoverable} object.
   * @param encoded representing serialized {@link Discoverable}
   * @return {@code null} if encoded bytes are null; else an instance of {@link Discoverable}
   */
  static Discoverable decode(byte[] encoded) {
    if (encoded == null) {
      return null;
    }
    return GSON.fromJson(new String(encoded, Charsets.UTF_8), Discoverable.class);
  }

  private DiscoverableAdapter() {
  }

  /**
   * SerDe for converting a {@link Discoverable} into a JSON object
   * or from a JSON object into {@link Discoverable}.
   */
  private static final class DiscoverableCodec implements JsonSerializer<Discoverable>, JsonDeserializer<Discoverable> {

    @Override
    public Discoverable deserialize(JsonElement json, Type typeOfT,
                                    JsonDeserializationContext context) throws JsonParseException {
      JsonObject jsonObj = json.getAsJsonObject();
      final String service = jsonObj.get("service").getAsString();
      String hostname = jsonObj.get("hostname").getAsString();
      int port = jsonObj.get("port").getAsInt();
      final InetSocketAddress address = new InetSocketAddress(hostname, port);
      return new DiscoverableWrapper(new Discoverable() {
        @Override
        public String getName() {
          return service;
        }

        @Override
        public InetSocketAddress getSocketAddress() {
          return address;
        }
      });
    }

    @Override
    public JsonElement serialize(Discoverable src, Type typeOfSrc, JsonSerializationContext context) {
      JsonObject jsonObj = new JsonObject();
      jsonObj.addProperty("service", src.getName());
      jsonObj.addProperty("hostname", src.getSocketAddress().getHostName());
      jsonObj.addProperty("port", src.getSocketAddress().getPort());
      return jsonObj;
    }
  }
}
