# RocketMQ 生产问题挑战系统

欢迎来到 RocketMQ 生产问题挑战！这是一个完整的实战训练系统，帮助你从零到精通 RocketMQ。

## 🎯 项目简介

这是一个基于真实生产环境问题设计的 RocketMQ 学习系统，包含 **12 个难度递增的挑战关卡**，涵盖从基础使用到架构设计的完整知识体系。

### 特色

- ✅ **真实场景**：每个挑战都来自真实的生产环境问题
- ✅ **循序渐进**：从简单到复杂，难度梯度合理
- ✅ **完整代码**：提供 Buggy、Fixed、Best 三个版本
- ✅ **详细文档**：每个 Level 都有完整的设计文档和测试指南
- ✅ **实战导向**：可以直接运行测试，观察问题现象

## 📊 挑战体系

### 基础篇 (Level 1-3) ⭐-⭐⭐⭐

**学习目标：** 掌握 RocketMQ 基本使用和常见问题处理

| Level | 问题 | 核心技术 | 难度 | 状态 |
|-------|------|---------|------|------|
| Level 1 | 资源泄漏问题 | Producer 生命周期、连接池 | ⭐ | ✅ 已完成 |
| Level 2 | 消息发送失败与重试 | 重试策略、异步发送、超时配置 | ⭐⭐ | ✅ 已完成 |
| Level 3 | 消息重复消费与幂等性 | 幂等性、去重表、分布式锁 | ⭐⭐⭐ | ✅ 已完成 |

**预计学习时间：** 1-2 周

---

### 进阶篇 (Level 4-6) ⭐⭐⭐-⭐⭐⭐⭐⭐

**学习目标：** 掌握性能优化、顺序消息和事务消息

| Level | 问题 | 核心技术 | 难度 | 状态 |
|-------|------|---------|------|------|
| Level 4 | 消息积压问题 | 消费并发度、批量处理、性能优化 | ⭐⭐⭐ | ✅ 已完成 |
| Level 5 | 顺序消息混乱 | 顺序消息、队列选择、顺序消费 | ⭐⭐⭐⭐ | ✅ 已完成 |
| Level 6 | 事务消息问题 | 事务消息、Half消息、事务回查 | ⭐⭐⭐⭐⭐ | ✅ 已完成 |

**预计学习时间：** 2-3 周

---

### 高级篇 (Level 7-9) ⭐⭐⭐⭐

**学习目标：** 掌握延时消息、过滤、死信队列等高级特性

| Level | 问题 | 核心技术 | 难度 | 状态 |
|-------|------|---------|------|------|
| Level 7 | 延时消息与定时任务 | 延时消息、时间轮算法、任务取消 | ⭐⭐⭐⭐ | 📝 设计中 |
| Level 8 | 消息过滤与标签路由 | Tag过滤、SQL92过滤、消息路由 | ⭐⭐⭐ | 📝 设计中 |
| Level 9 | 死信队列与消息重试 | 死信队列、重试机制、异常处理 | ⭐⭐⭐⭐ | 📝 设计中 |

**预计学习时间：** 3-4 周

---

### 架构篇 (Level 10-12) ⭐⭐⭐⭐⭐-⭐⭐⭐⭐⭐⭐

**学习目标：** 掌握链路追踪、流控、多机房容灾等架构级能力

| Level | 问题 | 核心技术 | 难度 | 状态 |
|-------|------|---------|------|------|
| Level 10 | 消息轨迹与链路追踪 | 消息轨迹、TraceId、OpenTelemetry | ⭐⭐⭐⭐ | 📝 设计中 |
| Level 11 | 消息优先级与流控 | 优先级调度、令牌桶、流控 | ⭐⭐⭐⭐⭐ | 📝 设计中 |
| Level 12 | 多机房容灾与消息同步 | Dledger、主备切换、脑裂 | ⭐⭐⭐⭐⭐⭐ | 📝 设计中 |

**预计学习时间：** 4-6 周

---

## 🚀 快速开始

### 1. 环境准备

```bash
# 安装 RocketMQ（使用 Docker）
docker run -d \
  --name rmqnamesrv \
  -p 9876:9876 \
  apache/rocketmq:5.1.0 \
  sh mqnamesrv

docker run -d \
  --name rmqbroker \
  --link rmqnamesrv:namesrv \
  -p 10911:10911 -p 10909:10909 \
  -e "NAMESRV_ADDR=namesrv:9876" \
  apache/rocketmq:5.1.0 \
  sh mqbroker
```

### 2. 启动挑战项目

```bash
cd rmq-challenge
mvn spring-boot:run
```

### 3. 开始第一个挑战

```bash
# 查看 Level 1 帮助
curl "http://localhost:8070/challenge/level1/help"

# 测试 Buggy 版本
curl "http://localhost:8070/challenge/level1/batchSend?count=50"

# 查看健康状态
curl "http://localhost:8070/challenge/level1/health"
```

---

## 📚 文档导航

### 核心文档

- **[CHALLENGES.md](CHALLENGES.md)** - 完整的挑战列表和详细说明
- **[CHALLENGES-SUMMARY.md](CHALLENGES-SUMMARY.md)** - Level 1-6 总结和 Level 7-12 概览

### Level 1-6 文档（已完成）

- **Level 1**: 代码中有详细注释
- **[LEVEL2-HINTS.md](LEVEL2-HINTS.md)** - Level 2 提示
- **[LEVEL3-ANALYSIS.md](LEVEL3-ANALYSIS.md)** - Level 3 分析
- **[LEVEL3-QUICK-TEST.md](LEVEL3-QUICK-TEST.md)** - Level 3 快速测试
- **[LEVEL4-GUIDE.md](LEVEL4-GUIDE.md)** - Level 4 指南
- **[LEVEL4-QUICK-TEST.md](LEVEL4-QUICK-TEST.md)** - Level 4 快速测试
- **[LEVEL5-QUICK-TEST.md](LEVEL5-QUICK-TEST.md)** - Level 5 快速测试
- **[LEVEL6-QUICK-TEST.md](LEVEL6-QUICK-TEST.md)** - Level 6 快速测试

### Level 7-12 设计文档

- **[LEVEL7-DESIGN.md](LEVEL7-DESIGN.md)** - 延时消息与定时任务
- **[LEVEL8-DESIGN.md](LEVEL8-DESIGN.md)** - 消息过滤与标签路由
- **[LEVEL9-12-DESIGN.md](LEVEL9-12-DESIGN.md)** - 死信队列、链路追踪、流控、容灾

---

## 🎓 学习路径

### 推荐学习顺序

```
第1周：Level 1-2
  ├─ 理解 Producer/Consumer 生命周期
  ├─ 掌握同步/异步发送
  └─ 学习重试策略

第2周：Level 3-4
  ├─ 理解幂等性设计
  ├─ 掌握消息去重
  └─ 学习性能优化

第3周：Level 5-6
  ├─ 理解顺序消息
  ├─ 掌握事务消息
  └─ 学习分布式一致性

第4周：Level 7-8
  ├─ 理解延时消息
  ├─ 掌握消息过滤
  └─ 学习消息路由

第5周：Level 9-10
  ├─ 理解死信队列
  ├─ 掌握链路追踪
  └─ 学习可观测性

第6周：Level 11-12
  ├─ 理解优先级调度
  ├─ 掌握流控机制
  └─ 学习多机房容灾
```

### 学习建议

1. **不要跳级**：按顺序完成，每个 Level 都是后续的基础
2. **动手实践**：运行代码，观察问题，自己修复
3. **理解原理**：不要只看答案，理解为什么这样设计
4. **记录笔记**：记录每个问题的分析过程和知识点
5. **查阅文档**：养成查阅官方文档的习惯

---

## 💡 核心知识点

### 基础知识（Level 1-3）

- Producer/Consumer 生命周期管理
- 同步发送 vs 异步发送 vs 单向发送
- 重试策略：固定间隔 vs 指数退避
- 幂等性设计：去重表、分布式锁、业务状态机

### 进阶知识（Level 4-6）

- 消费并发度配置与优化
- 批量处理与异步化改造
- 顺序消息：MessageQueueSelector、顺序消费
- 事务消息：Half消息、事务回查、最终一致性

### 高级知识（Level 7-9）

- 延时消息：18个延时等级、时间轮算法
- 消息过滤：Tag过滤、SQL92过滤、性能对比
- 死信队列：重试机制、异常分类、消息重投

### 架构知识（Level 10-12）

- 消息轨迹：TraceId传递、链路追踪
- 优先级调度：多队列、令牌桶、流控
- 多机房容灾：Dledger、主备切换、脑裂防护

---

## 🏆 完成标准

### Level 1-6（基础篇）

- ✅ 理解每个问题的本质
- ✅ 能够独立修复 Bug
- ✅ 通过所有测试用例
- ✅ 能够解释解决方案的原理

### Level 7-9（进阶篇）

- ✅ 能够设计解决方案
- ✅ 考虑多种边界情况
- ✅ 进行性能测试
- ✅ 能够对比不同方案的优缺点

### Level 10-12（高级篇）

- ✅ 能够设计完整架构
- ✅ 考虑容灾和高可用
- ✅ 编写监控和告警
- ✅ 能够处理生产级问题

---

## 🔧 技术栈

- **Java**: 21
- **Spring Boot**: 3.x
- **RocketMQ**: 5.1.0
- **RocketMQ Client**: rocketmq-v5-client-spring-boot-starter

---

## 📈 进度追踪

你可以在这里记录你的学习进度：

- [ ] Level 1: 资源泄漏问题
- [ ] Level 2: 消息发送失败与重试
- [ ] Level 3: 消息重复消费与幂等性
- [ ] Level 4: 消息积压问题
- [ ] Level 5: 顺序消息混乱
- [ ] Level 6: 事务消息问题
- [ ] Level 7: 延时消息与定时任务
- [ ] Level 8: 消息过滤与标签路由
- [ ] Level 9: 死信队列与消息重试
- [ ] Level 10: 消息轨迹与链路追踪
- [ ] Level 11: 消息优先级与流控
- [ ] Level 12: 多机房容灾与消息同步

---

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

---

## 📄 许可证

MIT License

---

## 🎉 致谢

感谢 Apache RocketMQ 社区提供的优秀消息中间件！

---

**准备好开始你的 RocketMQ 挑战之旅了吗？** 🚀

从 [Level 1](CHALLENGES.md#level-1-资源泄漏问题) 开始吧！
