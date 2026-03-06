package com.clawai.gatedemo.gate.protocol.model;

import java.util.Map;

public interface MessageBody {

    byte[] toBytes();

    void fromBytes(byte[] bytes);

    String toJson();

    void fromJson(String json);

    Map<String, Object> getData();

    void setData(Map<String, Object> data);
}
