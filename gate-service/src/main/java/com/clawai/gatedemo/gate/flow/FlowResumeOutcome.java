package com.clawai.gatedemo.gate.flow;

/**
 * AUTH 处理后返回给客户端的会话状态，对齐 proto {@code ResumeStatus} 枚举。
 *
 * <p>设计要点（design.md §3 / §4）：
 * <ul>
 *   <li>{@link #NEW}：服务端首次 mint flow，或客户端未带 flowId；</li>
 *   <li>{@link #RESUMED}：同 flowId 在同实例成功换绑 Channel；</li>
 *   <li>{@link #REJECTED_EXPIRED}、{@link #REJECTED_MISMATCH}、{@link #REJECTED_OWNER_OTHER}
 *       均触发同请求内 <b>降级 NEW</b>，对外仍返回新 flowId，
 *       但 {@code resume_status} 保留拒绝原因以便客户端决策。</li>
 * </ul>
 */
public enum FlowResumeOutcome {
    NEW,
    RESUMED,
    REJECTED_EXPIRED,
    REJECTED_MISMATCH,
    REJECTED_OWNER_OTHER
}
