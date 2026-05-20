package com.clawai.gatedemo.gate.flow;

/**
 * 客户端 / 服务端在 AUTH 阶段协商的能力位（B1：{@code add-flow-downstream-buffer}）。
 *
 * <p>定义见 {@code openspec/changes/add-flow-downstream-buffer/design.md} §2。
 * 协商规则：{@code server_features = client_features & server_advertised_features}。
 */
public final class FlowFeatures {

    private FlowFeatures() {}

    /** 客户端 / 服务端均支持下行帧 gwSeq stamp（FLAG_HAS_GW_SEQ）。 */
    public static final int GW_SEQ = 0x1;

    /** 客户端 / 服务端均支持 RESUME 成功后从 buffer 重放。 */
    public static final int RESUME_REPLAY = 0x2;

    public static boolean supportsGwSeq(int features) {
        return (features & GW_SEQ) != 0;
    }

    public static boolean supportsResumeReplay(int features) {
        return (features & RESUME_REPLAY) != 0;
    }

    public static int negotiate(int clientFeatures, int serverAdvertised) {
        return clientFeatures & serverAdvertised;
    }

    public static int buildServerAdvertised(boolean advertiseGwSeq, boolean advertiseReplay) {
        int v = 0;
        if (advertiseGwSeq) v |= GW_SEQ;
        if (advertiseReplay) v |= RESUME_REPLAY;
        return v;
    }
}
