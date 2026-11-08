# WebHTV Agent Contract

This file applies to the whole repository. Keep work correct, narrow, reversible, and fast. A nested `AGENTS.md` may add path-specific rules but may not weaken the safety, scope, or rollback rules here.

## 1. Start with a bounded lane

Before the first edit, state one completion sentence, the allowed paths, protected pre-existing dirty paths, the current local time and timezone, a realistic total-duration estimate with expected finish time, and the cheapest decisive verification. For multi-phase work, give a short estimate for each phase. Treat these estimates as execution targets rather than guard gates; when the estimate is reached or slips materially, stop optional work, state the cause, and continue with the narrowest completion path or a materially different shortest route. Do not let a stale estimate justify repeated checks, open-ended research, or an unfinished handoff. Use the smallest applicable lane:

Estimate elapsed wall-clock time for the current Codex agent in this workspace, not the time a human engineer or team would need. Base the estimate on the actual repository state, available tools, warm caches, ABI/build scope, device availability, and unavoidable network/build/user wait; split those phases when they materially differ and give the expected local finish time. Do not quote person-days or staffing estimates unless the user explicitly asks for them.

### Governance-maintenance fast path

When the task only edits `AGENTS.md`, `.codex/skills/**`, `.codex/scripts/**`, or their review document:

- Diagnose from the user's observed failure and the current diff only. Do not search the web, reread general best-practice sources, forward-test, create a temporary repository, or expand the methodology unless the user explicitly asks or one concrete unresolved fact blocks the edit.
- Select one root cause, apply one bounded patch, then run exactly one combined validation pass covering only changed artifacts. If it passes, stop immediately and hand off; do not perform reassurance checks.
- Do not update the same rule in every layer by default. Put the decision rule in `AGENTS.md`, domain-only detail in the Skill, and deterministic behavior in the script. Update another layer only when its behavior would otherwise contradict the fix.
- The repository task guard is not required for maintenance of the guard itself or its instruction files. Preserve unrelated dirty files and do not commit/tag unless the user explicitly requests it or this maintenance is already an isolated task-owned change.
- Maximum normal tool sequence: one inspection call, one patch call, one combined validation call. A fourth call requires a failed validation or a concrete blocker, and the reason must be stated before running it.

- A small bug is `quick-fix` by default. Do not promote it to architecture, broad research, or native work merely because more investigation is possible.
- Lane names describe risk and workflow only. They do not impose elapsed-time, changed-file, cycle-count, checkpoint, or replan gates.
- Optimize for shortest elapsed time by removing redundant exploration, repeated commands, speculative scope, and low-value validation. Never gain speed by dropping required behavior, risk-driven verification, rollback, or the requested completion target.
- Under a user-stated time constraint, never repeat a successful or otherwise conclusive check, and never expand research after the available evidence can decide the approved implementation. A retry requires a relevant edit or an inconclusive result; broader research requires one named unresolved fact that can materially change the decision.
- When the user states a time budget or deadline, treat it as the execution target for the current turn and reserve roughly the final quarter for verification, documentation, commit, tag, and handoff. Choose one shortest evidence-backed route, run each expensive build/test at most once unless a relevant edit or an inconclusive result requires a retry, and stop optional research or cleanup before the budget is exhausted. Keep all code, lock, artifact, and task-document changes for one approved unit in one guard session and one commit when possible; only make a second documentation-only closure commit when an unavoidable post-commit ID/tag must be recorded, with no extra build or research pass. This routing rule never lowers correctness, rollback, or completion requirements.
- Classify command failures before asking for approval: a repository file-mode or invocation error is fixed by using the correct in-scope invocation once (for example `bash ./gradlew`), while sandbox/network/permission errors are the cases that warrant an escalation request. Do not spend a second attempt or approval round on the wrong failure class.
- Do not widen declared behavior or paths without explicit user approval. Split genuinely large work into independently useful units while preserving the original completion target.
- Except for the governance-maintenance fast path above, run `bash .codex/scripts/task_guard.sh start --id <task-id> --mode <lane> --scope <path>...` before code edits. `check` is an optional read-only safety audit; it is not required after each cycle, and `finish` performs the final safety check itself.
- A timeout may bound one potentially hung command; it must not bound the task. Diagnose the timeout and continue with a safer, narrower, or resumable command.

## 2. Scope is closed by default

- Modify only the declared paths and behavior needed for the completion sentence. No unrelated refactor, cleanup, formatting sweep, dependency upgrade, generated-file churn, or speculative abstraction.
- A failing unrelated test, warning, or nearby defect is not part of the task. Report it; do not fix it unless it blocks the requested result and the user approves the expansion.
- Preserve every file dirty before task start. Never include an initially dirty path in task scope or a task commit unless the user explicitly assigns that work and it has been isolated safely.
- If the proposed fix crosses a new module, changes public behavior/API, or changes dependency or binary ownership, narrow it to the smallest useful unit. Ask the user only when that unit expands the authorized behavior or paths.

## 3. Search and diagnosis must earn their cost

- For a bug, follow: reproduce or establish evidence -> inspect the exact path and callers -> form one falsifiable cause -> make the smallest fix -> run one targeted verification.
- Search locally first with exact symbols/errors. Internet, upstream history, papers, broad issue searches, and whole-repository archaeology are escalation steps, not defaults for `quick-fix`.
- Expand search only when the current evidence cannot decide the next action. Use at most two query reformulations for a quick fix and three for one material upstream question; prefer primary code/docs/issues over repetitive posts.
- Capture a command's full output once and filter/read that saved result. Do not rerun the same test or build with different output filters.
- Run each final syntax/lint/test check once. Repeat it only after a relevant edit or when the first result is inconclusive; never re-check an unchanged successful result for reassurance.
- After two failed attempts with the same hypothesis, discard it and choose a materially different path. Repetition without new evidence is prohibited; task completion remains required.

## 4. Verification is risk-based and minimal

- Start with the cheapest deterministic check that can falsify the change: focused unit test, compilation target, static check, or one representative device scenario.
- One conclusive pass is enough unless the change concerns flakiness, concurrency, performance variance, native lifecycle, ABI, or device-specific behavior.
- Do not run full Gradle matrices, all ABIs, native rebuilds, broad device suites, fuzzing, or unrelated tests unless the changed contract requires them or the user requests them.
- Do not weaken a failing gate. Classify the failure as regression, environment, or stale expectation. Expand work only if fixing it is within the declared scope.
- Quality floor: do not trade away existing behavior, correctness, security, compatibility, material performance, or task completion merely to meet the clock. Time pressure removes redundant work; it never authorizes an unverified shortcut.

## 5. Context recovery

- Create a durable checkpoint only when a task is genuinely likely to cross a session/compaction boundary, before a risky long-running operation, or when changing functional stage/repository. Never require it because of elapsed minutes, cycle count, or changed-file count.
- A checkpoint records objective, lane/scope, branch/HEAD, protected dirty paths, completed evidence, files changed, verification/result, unresolved risk, rollback anchor, and exactly one next action.
- For long-lived work, update the relevant tracked document with `apply_patch`; the task guard does not own or force progress checkpoints.
- For a task expected to survive session/compaction, create one task-owned `docs/<task-id>-<slug>.md`. Keep a short top or tail `Recovery anchor` containing the objective and acceptance criteria, plan status, current files/symbols, completed actions and results, unverified worktree edits, unresolved risks, and exactly one next action.
- Update the recovery anchor when material state changes or immediately before a likely compaction/long-running operation. The conversation plan is transient; the task document is the durable execution state.
- After compaction or resumption, recover in this order: workspace and Git evidence (`git status`, branch/HEAD, scoped diff) -> the latest recovery anchor/task document -> the compaction summary -> older conversation plan. Reconcile discrepancies and preserve existing unverified changes.
- Once recovery is reconciled, execute the documented single next action. Do not restart planning, repeat completed repository-wide searches, reread completed code paths, or discard partial edits unless the workspace or task document proves they are invalid. Never reconstruct exact hashes or decisions from memory.

## 6. Commit and recovery tag are part of code completion

- One task guard session equals one atomic logical change. If a task needs another logical commit, finish this unit and start a new guard session.
- A code change is not complete until its targeted verification is recorded, its task-owned files are committed atomically, and that commit has a unique annotated local recovery tag.
- **Explicit closure fast path:** when the user confirms the tested behavior is acceptable (for example, “没问题”, “通过”, or equivalent) and asks to commit or create a tag, that confirmation closes optional verification for the approved scenario. Stop immediately; do not capture more logs or screenshots, run adjacent-media/device cases, start another build/test, inspect upstream, perform reassurance checks, or broaden documentation before closing.
- After an explicit closure request, the next tool call must perform the closure unless one concrete unknown safety fact makes that impossible. The normal sequence is at most: one minimal patch to the existing task document only when required to record already-known results, then one `task_guard.sh finish` call that commits and tags, then report the commit and tag. Do not run a separate guard `check`, status/diff review, or tag verification because `finish` already enforces scope and tag creation. If the verified change is already committed, create the requested annotated tag immediately without reopening documentation.
- Treat this as a latency-critical execution target: normally complete the requested local commit/tag within 60 seconds and start the tag-producing command in the first possible tool round trip. A direct command failure or genuine safety blocker is the only reason to exceed this target; report and fix only that direct cause. User confirmation never authorizes skipping an actually failed required gate, but a missing optional or neighboring test is not a blocker after the user accepts the observed behavior.
- When the same message also authorizes a push, push only the named/current branch and newly created tag after local closure. Do not fetch, compare, or revalidate first unless the push itself reports a conflict or rejection.
- Finish with `bash .codex/scripts/task_guard.sh finish --verified <evidence> --commit-message <message>`. The script must stage only task-owned paths and create `recovery/<task-id>/<timestamp>`; never hand-stage unrelated dirty work.
- After the commit succeeds, create its recovery tag immediately with one local, non-interactive `git tag -a` command with tag signing disabled. The tag phase should normally finish within 5 seconds; do not insert builds, tests, searches, network calls, repeated diff reviews, or redundant tag checks between commit and tag.
- Treat a successful tag command as sufficient. If it fails, capture the error once, fix its direct cause, and retry only after the command materially changes; never loop an unchanged tag command.
- If verification fails, scope overlaps pre-existing dirty work, or a safe atomic commit cannot be formed, do not commit/tag. Record durable state when needed and report the exact blocker.
- Assessment-only work does not authorize production edits. Tags point only to committed, verified states. Never push commits/tags, move a published tag, rewrite history, or publish artifacts without explicit authorization.

## 7. Best-practice design research

The mandatory design-research gate below applies repository-wide to every material new feature, optimization, architecture, compatibility, security, dependency, or cross-module requirement, as well as every upstream merge candidate. Use the relevant domain Skill when one exists. Simple text/configuration changes and narrowly localized fixes with an already established design are exempt unless the user explicitly requests research.

## 8. Upstream and player dependency work

For FFmpeg, media/Media3/nextlib, Exo, mpv, mpv-android, libplacebo, JNI, native binaries, locks, patches, or binary packaging, read and follow `.codex/skills/upstream-integration-governor/SKILL.md` and only the references it marks for the current lane.

`.codex/skills/upstream-integration-governor/` is the runtime canonical copy for this repository. `webhome-devkit/skills/upstream-integration-governor/` is a reference/distribution mirror for other developers and is not an additional instruction authority or loading path. Whenever the canonical Skill changes, update the mirror in the same governance-maintenance task and validate that the mirrored files are byte-identical; never let the two copies evolve independently.

### New-session routing for the upstream merge plan

- The user does not need to paste a bootstrap prompt. Treat short requests such as `下一个任务`, `继续上游播放器依赖合并计划`, `梳理新的上游更新`, or equivalent wording as sufficient invocation of the upstream Skill. The expected branch for this repository plan is `fongmi-sync`; inspect the actual branch/HEAD and preserve every pre-existing dirty path before any mutation.
- Unless the newest user message explicitly approves implementation, the first response is assessment-only and read-only. Fully read `README.md`, this file, the active `docs/upstream-player-dependency-merge-assessment-*.md`, and only the task documents directly related to the candidate. Use the assessment ledger, Git history, and current code to choose the next genuinely unfinished and unimplemented task in the order `Exo -> MPV -> common`.
- Before proposing work, determine whether another commit already implemented it, partially covered it, superseded it, or made it irrelevant. Recommend `忽略` directly when it is already covered or has no current product value; do not relabel it as new implementation work.
- Explain the user-visible playback capability in ordinary language. Include all related repositories and full 40-character commit IDs, cross-repository relationships, benefits, disadvantages and risks, effects on existing behavior, compatibility, performance and package size, best-practice status, required WebHTV adaptation, a clear `实施`/`暂缓`/`忽略` recommendation, and the smallest staged implementation and verification plan.
- Use this first-response shape and then stop for explicit approval: `任务编号和名称`, `所属分类`, `要实现的实际能力`, `当前项目已有实现`, `涉及仓库及完整 commit ID`, `收益`, `缺点与风险`, `与现有功能的关系`, `建议`, `最小实施步骤`, `预计需要的验证`. Do not modify code, locks, patches, artifacts, or runtime behavior during this selection response.
- A later explicit `开始实施` authorizes only the unambiguous currently recommended task. Treat all existing behavior, compatibility and material performance as acceptance contracts: the implementation must not knowingly cause direct or indirect regressions or reduce current performance. Adapt or reject the upstream design when needed, use the narrowest risk-proportionate verification, and do not claim completion while a material regression risk remains unresolved.

- Assessment and implementation are separate authorities. Enumerate every in-scope upstream commit with a full 40-character ID and a disposition; never implement an unapproved stage.
- Group related cross-repository behavior into independently reversible `Exo`, `MPV`, or `common` stages. Preserve the decision/implementation order `Exo -> MPV`.
- Continue the active assessment from `docs/upstream-player-dependency-merge-assessment-2026-08-20.md`; do not redo completed analysis unless source heads or material evidence changed.
- Resolve every actionable stage to the stable task ID recorded in the assessment index before creating or updating task documentation. Use the existing families `E*`/`E*-*` for Exo, `E-SP*` for Exo performance, `P*`/`P*-*` for MPV, and `C*`/`C*-*` for common work. Never renumber, recycle, or replace an assigned ID with a generic sequence.
- One started upstream task has exactly one durable file named `docs/<TASK-ID>-<slug>.md`. That file owns the task's best-practice research, local-code review, decision, implementation history, repeated fixes, verification, commits/tags, rollback, current status, and next action. Append later work to it; do not create parallel `plan`, `assessment`, `implementation`, `fix`, or dated follow-up files for the same task.
- The assessment document is the sole exception: it remains the cross-task index and exhaustive upstream commit ledger. It links each active task to its unique document but does not replace that document's implementation record.

### Mandatory design-research gate

Before changing code, locks, build scripts, patches, artifacts, or runtime behavior for any upstream candidate or material new requirement, complete a decision-ready best-practice review. A material requirement includes a new user-facing capability or a change to architecture, performance, playback behavior, startup/seek, decoder or renderer selection, network/proxy handling, compatibility, native/binary packaging, public API, security, data ownership, or a cross-module contract.

- Before implementation, create or update the task's unique `docs/<TASK-ID>-<slug>.md` and link it from the assessment index. Its best-practice section is the durable plan; do not create a second `plans/` document or treat a chat response as the record.
- Search every applicable evidence class: exact upstream source/commits/tests; official specifications and platform/project documentation; upstream PRs, issues, reverts, and maintainer discussions; mature related-project code and tests; and relevant papers, technical posts, blogs, benchmarks, or field reports. If a class is genuinely inapplicable, record the reason in the plan rather than silently skipping it.
- Read the actual sources, not search-result snippets. Record URL or repository path, revision/commit, access date, evidence grade, the supported claim, WebHTV applicability, caveats, and the decision impact. Use the configured proxy when network access is needed; if required research cannot be obtained, mark the stage incomplete and do not claim a best-practice conclusion.
- Review the current WebHTV implementation and call/data flow at concrete file and symbol locations. Identify existing equivalent or partial behavior, local safeguards, consumers, build reachability, and any later local fixes before selecting a design.
- The plan must compare at least `no change`, the unmodified upstream approach, and a narrow WebHTV-adapted approach (plus other credible alternatives when relevant). Explicitly decide whether to optimize, correct, supplement, or reject the upstream proposal for this project, and explain the tradeoffs for correctness, compatibility, performance, quality, lifecycle, ABI, security, provenance, maintenance, validation, rollout, and rollback.
- Do not implement until the plan contains a recommendation, acceptance criteria, and rollback path and the user explicitly approves the proposed stage. Keep research bounded to one decision-shaped question at a time: stop when additional sources no longer could change the design or decision, and record the unresolved gate instead of browsing aimlessly.

## 9. Enforcement boundary

`AGENTS.md` is an instruction layer, not a security boundary. The task guard only enforces declared paths, protected initial dirty files, branch/HEAD stability, atomic task commits, and recovery tags when invoked. It does not enforce elapsed time, cycle counts, changed-file limits, checkpoints, replans, or state archives. Git hooks are not the primary mechanism because ordinary clones do not reliably enable tracked hooks and client hooks can be bypassed. CI remains the correct place for organization-wide mandatory checks.


## 10. 构建脚本错误清单（每轮强制预检，不许重犯）

本节记录本仓库 CI 已经踩过的坑。任何改动构建脚本、签名、资源目录或发版步骤之前，必须先逐条对照；dispatch 之前必须完成本节的预检。一条不满足就不许触发构建。

1. **v1 签名只能从「完全未签名的 APK」一次签成**。apksigner 无法给已经带 v2/v3 签名的 APK 追加 v1（即使加 `--v1-signing-enabled true --min-sdk-version 21` 也无效）。minSdk 24 + AGP 9 默认只出 v2/v3；`signingConfigs { enableV1Signing = true }` 实测无效。正确做法：CI 工作副本把 `signingConfig` 置空，让 Gradle 输出未签名 APK，再用 apksigner 一次签 v1+v2+v3。
2. **校验口径**：`apksigner verify --verbose` 在 minSdk 24 的 APK 上会报 `Verified using v1 scheme (JAR signing): false`，即使 META-INF 里确实有 v1 签名。必须用 `apksigner verify --min-sdk-version 21 --verbose`，并且客观统计 `META-INF/*.RSA|DSA|EC|SF` 数量 ≥ 1。不能只看默认的 scheme 行。
3. **失败 annotation 必须单行化**。多行输出写成 `::error` 时只会显示第一行（曾只显示一个「Verifies」，白耗一轮构建）。所有诊断先把换行压成空格再输出，并把 apksigner 全文与 META-INF 数量一起打进 annotation。
4. **新增 Java 引用先查 import**。曾因 `leanback SiteDialog.java` 用了 `R.string.script_reloaded` 却漏 `import com.fongmi.android.tv.R`，编译直接失败。改动后必须逐个核对新增 R 引用与 import。
5. **drawable 禁止跨变体重名**。曾把 `ic_action_check.xml` 同时放进 main 与 mobile 导致资源冲突；新建图标一律用带用途前缀的唯一名（如 `ic_script_check`、`ic_popup_import`），并确认 main / mobile / leanback 三处不重名。
6. **资源目录改名必须同步 workflow 门禁路径**。曾把 `values-zh-rCN` 改成 `values-zh`，workflow 里两行 grep 还指旧路径，`set -e` 直接退出。凡移动/改名 `res/values-*`，同步搜索 `.github/workflows/` 里的硬编码路径。
7. **Gradle 日志要留得下来**：`tee` + `set -o pipefail` + 独立的失败诊断步骤；失败先读 annotation 与日志定位，再改，不盲目重跑。
8. **不检查就出包、重复构建一律禁止**。dispatch 前固定预检：该 HEAD 已有 run 数必须为 0、4 个签名 secrets 齐全、workflow YAML 可解析、源码门禁断言本地模拟全过、tag 不冲突、门禁/校验步骤的关键字齐全。一次 HEAD 只 dispatch 一次；修复构建也不许在同一 HEAD 上重复触发。CI `completed/success` 且 8 个资产齐全之后，才允许报「构建成功」。
9. **CI 全绿 ≠ 用户工具通过**。曾把「apksigner verify 通过 + v1:true + CN 正确」当成验收完成，而测试人员用 MT 管理器看到「签名状态：校验不通过」——方案显示 V1+V2+V3 齐全，说明缺 v1 已修好，但 MT 会逐个校验 `META-INF/` 里的 JAR 签名文件；Gradle 未签名包可能残留第三方 `*.SF/.RSA/.DSA/.EC` 与旧 `MANIFEST.MF`，apksigner 只追加不清理，MT 遇到对不上当前证书的那套就判不通过。**补签前必须先 `zip -d` 清干净所有旧签名条目**；断言从「≥1 个签名文件」收紧为「恰好 1 个签名块 + 无 apksigner WARNING」。
10. **端到端验证不能只靠 CI 自述**。沙盒出网被网关挡住，Release 资产与 Actions artifact 均下载不了（403），所以每轮都必须如实写明「未做端到端验包」。修复方式：CI 生成 `dist/signature-report.txt`（每个 APK 的 META-INF 清单、apksigner verify 全文、证书、sha256）并作为 Release 资产上传，让用户能直接对照 MT 显示的指纹；不得在未声明这一限制的情况下说「签名已验证/完成」。用户工具报错时先问清具体文字与签名方案，不要凭 CI 结果反推「已经修好」。
11. **收紧门禁前先看正常输出长什么样**。曾为「更严谨」加了「拒绝任何 apksigner WARNING」，结果 run `42268600502` 倒在验签步骤：AGP 打的包必然对 `META-INF/app-metadata.properties`、`version-control-info.textproto`、`androidx.*.version` 报 `WARNING: ... not protected by signature`，这是标准提示，与签名有效性无关，MT 也不会因此判不通过。断言必须带白名单（只拦截 `not protected by signature` 之外的 WARNING），不能一刀切。新增任何「全量拒绝型」断言前，先跑一次看真实输出。



12. **搬入上游 Java 必须做「方法级可达性」检查，不能只查 import**。`import` 可达 ≠ 方法存在：第七/八批搬入 44+31 个类后，本地只校验了 import 与 R 引用，漏掉 若干公共类方法 / `Setting.resolveTmdbDetailLightTheme(int,boolean)` / `Util.resetApp()` 三个静态调用，以及 `Setting.clampTmdbDetailTheme(int)` 这个被调私有方法——这些只有到 CI 编译才会报 `cannot find symbol`。规定：每批搬入后，扫描新增 Java 对 `Setting` / `PlayerSetting` / `Prefers` / `Util` / `App` / `ResUtil` / `Notify` / `ImgUtil` / `VodConfig` / `SiteApi` 等公共类的 `Cls.method(` 调用，逐一对齐**当前分支状态**（含同批已补成员）里定义的方法；同时核对跨变体同名类（如 `VideoActivity.start(...)`）的重载参数在两个变体都存在。曾踩：`TmdbPersonDialog` 调 5 参 start、`TmdbNavigation` 调 6 参 start，两变体重载不齐就会编译失败。
13. **APK 变体数与校验断言不得硬编码**。曾把构建任务写死四行 `assemble*Release`、验签步骤写死 `test "$count" -eq 4`；用户只要「手机 v8a + TV v7a」两个包时就必须改 workflow，否则会多构建两个、或倒在该断言上白耗一轮 Actions。构建任务由 `workflow_dispatch` 输入 `apk_variants` 拼装（`all` = 四包，或逗号分隔指定），`expected_apk_count` 写进 `steps.build_apks.outputs`，验签步骤按输出值断言。collect / 补签 / 验签 / manifests / 发版全部用 `dist/*.apk` glob 动态遍历，增减变体不再需要改脚本。
14. **批量删除代码块后必须检查「类闭合 } 之后是否残留方法」**。第十批清 TMDB 时把 `onPersonal` 从类内删走，却在两个设置页文件的 `}` 之后留下 3 行孤儿方法；javac 21 把它当 unnamed class，报 `unnamed class should not have package declaration` / `unnamed classes are a preview feature and are disabled by default`，构建直接失败。每次删除/搬移方法后，全库扫一遍「顶层类闭合行之后仍有非空代码」，并与成功构建的 main 对比排除误报（字符串里的 `{`、多个顶层类、匿名内部类都会让裸括号计数误报）。
15. **搬入上游 Java 后必须做「方法级 + 重载参数 + 实例方法」三重可达性检查，不能只查 import 与 R 引用**。第三次 CI run 42382714709 报了 10 个 `cannot find symbol`：8 处是 `LightDialog.apply(AlertDialog)`（分支精简版 LightDialog 没这个方法，需连 4 个 resolve* 与 applyAlertWindow / resizeAlertList 一起补，且不能覆盖分支已有的 applyWindow）、1 处 `AppBackup.fileName(boolean)`（只有无参版，同名方法存在不代表重载够用）、1 处 `site.getDisplayName()`（实例方法调用，静态 `Xxx.method(` 扫描抓不到）。修法：以成功构建的 main 为基线，对全库 Java 扫「项目类静态调用 / 已知实例方法 / 调用点实参个数」，凡上游有定义而分支无定义的一律补齐；匿名内部类、内部类 `X.Listener`、两个同名 Util、JDK `Class/Process/Proxy` 属误报，须排除。
16. **搬入上游代码后必须再扫「类内私有方法 / 常量 / 重载 / lambda 参数嵌套」四类隐形缺口**。第四次 CI run 42383352055 报的 10 个错误全是这类：`Setting.java` 调用了 `clampGlobalHistoryMode` / `clampSearchThread` 两个私有方法与 `GLOBAL_HISTORY_*` 三个常量却没定义；`ImgUtil.logo(view, logo)` 缺两参重载；`CustomWebView.sniff(Pattern)` 缺方法与字段（猫源设置中心要用）；`SettingEnhanceFragment` 把实验室入口 `view ->` 插进了 `customCsp` 的 lambda 体内，与外层 lambda 参数同名，javac 报 `variable view is already defined`。修法：以成功构建的 main 为基线，全库扫「本类调用但未定义而上游同类有定义」「全大写常量是否已定义」「静态调用的方法是否含所需重载参数」「新增 setOnClickListener 是否落在别的 lambda 块内」，四项都清零再出包。
17. **搬入上游 Java 后必须做「按包解析的 import 可达性」扫描，不能只看 import 语句里引用的类是否存在**。第五次 CI run 42384169843 报 `Setting.java:818 cannot find symbol`，原因是新增的 `isTouchOptimized()` 调用了 `Util.isLeanback()`，却没 `import com.fongmi.android.tv.utils.Util`。修法：对每个 Java 文件取 package 声明，收集显式 import、通配 import 覆盖的类、同包顶层类、文件内定义的类/枚举，再扫所有 `Xxx.` 调用；凡 `Xxx` 是项目顶层类却四者都不可见的一律补 import。注意 `Class.forName` 是 java.lang.Class，项目里恰好有个同名 bean 类属误报，须排除；多同名类必须按包建立多重映射，不能用字典覆盖。
18. **搬入上游 layout / Java 后必须按 variant 分别校验 style 的隐式 parent 与可见性**。第六次 CI run 42384891843 倒在 `:app:processMobileArm64_v8aDebugResources`，报 `style/Widget.WebHTV.LightDialog not found`：Android 的 style 名含点且未写 parent 时会隐式继承去掉末段的名字，`Widget.WebHTV.LightDialog.Label/Helper/Input` 三个都隐式指向被精简掉的空壳 `Widget.WebHTV.LightDialog`，而它又隐式指向 `Widget.WebHTV`。另外 main 的 `activity_app_branding.xml` 引用 `ToolbarTextAppearance`，mobile 的 styles.xml 有定义、leanback 没有，TV 变体编译时会挂。修法：main + mobile / main + leanback 各算一次可见 style 集合，扫全部 `@style/` 引用与无 parent 的带点 style 的隐式父，两变体都清零才出包；补定义优先放 main，且必须核对该 style 块引用的 color/drawable/parent 在分支都存在，不能整份覆盖上游 styles.xml（上游那份引用了已清除的追更/显示选项资源）。
19. **同一功能在 mobile / leanback 两个变体的同名设置页必须成对检查，且不能拿 debug 编译结果当 release 结论**。第七次 CI run 42386092445 里门禁与单测（只编译 mobile debug）全绿、release 才编译 leanback 时报 `SettingEnhanceActivity.java:93 variable view is already defined`——第五轮只修了 mobile 的 `SettingEnhanceFragment`，漏了 leanback 的 `SettingEnhanceActivity`，两处都是实验室入口 `view ->` 插进 `customCsp` 的 lambda 块。另外一次 `./gradlew` 跑多个 assemble 且未加 `--continue` 时，任一变体失败会连锁取消其它任务，`minifyMobileArm64_v8aReleaseWithR8` 报 `Compilation failed to complete` 属连锁失败，不要当成独立的 R8 问题去改混淆规则。修法：凡按变体新增/修改的 Java，两个 sourceSet 都过一遍括号、lambda 参数、import、Binding id；出包前明确知道 debug 门禁只覆盖 mobile。
20. **搬第三方库必须连它的可选/配套依赖一起核对，且 release 的 R8 结果只有真实出包才验证得到**。第九次 CI run 42388274148 前面全绿、倒在 `:app:minifyMobileArm64_v8aReleaseWithR8`：`Missing class org.tukaani.xz.*`（commons-compress 的 7z LZMA2/BCJ/Delta 编解码引用）。上游 app/build.gradle 是 `implementation libs.commons.compress` 与 `implementation libs.tukaani.xz` 成对出现，libs.versions.toml 也同时有 `tukaaniXz = "1.12"` 和 `tukaani-xz` catalog；第六批实验室只搬了前者。debug 编译不跑 R8，所以门禁/单测全过也查不出。修法：按上游原样补 version + catalog + implementation 三处；以后凡新增依赖，先 diff 上游同文件的依赖声明，把配套项一并搬运。
21. **搬上游 values 文件时不能因为「分支里已有同名 key」就删行，必须按 sourceSet 判断可见性**。第十次 CI run 42388955264 倒在 `:app:processLeanbackArmeabi_v7aReleaseResources`：`menu_lab.xml` / `menu_lab_detail.xml` 引用 `@string/nav_setting` 找不到。上游在 `app/src/main/res/values/lab_strings.xml` 定义了 `nav_setting=设置`，第六批实验室搬该文件时看到分支 mobile 的 strings.xml 已有同名项，就把搬入行删了；但 main 资源包编译时看不到 mobile/leanback 的定义，于是 main 的 menu 引用悬空。修法：main 目录下的文件（menu/layout/Java）引用的资源，必须在 main 的 values 里就有定义；mobile/leanback 的同名项只能覆盖显示值、不能替代 main 定义。搬 values 前先按 `app/src/xxx/` 分组比对上游与分支的 key 集合，差异项一律补回。


### 每次出包前的固定预检清单

- 当前 `main` HEAD 与预期一致，且 `actions/runs?head_sha=<HEAD>` 返回 0
- secrets：`RELEASE_KEYSTORE_BASE64` / `RELEASE_KEY_ALIAS` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_PASSWORD`
- workflow YAML 可解析；关键步骤计数正确（未签名构建、补签、v1 校验、REST 发版）
- 源码门禁断言逐条本地模拟，失败项为 0
- 预计 tag 不存在
- 正式签名 `CN=xbox-re`、applicationId `.xbox` 共存、APK 数量与本次 `apk_variants` 输入一致（默认 4，可指定 2）
- 本轮只 dispatch 一次；轮询到 completed 才下结论
- 断言若用「拒绝任何 WARNING / 任何差异」这种全量口径，必须确认过正常输出里没有该类内容
- `signature-report.txt` 已生成并上传；若资产下载受限，明确告知「未端到端验包」，附证书 SHA256 指纹供用户对照

13. **资源 / import / 方法级可达性校验必须只用「当前分支 tree」**，禁止把上游缓存混进资源池。
    前九批多次报「R 引用 0 缺失」，实为把上游 webhtv 缓存算作已定义；改用分支自身 zipball
    tree 重算后才发现 188 项缺失。每批搬入后必须核对「新增文件 + modified 文件 diff 新增行」
    的全部 `R.x.y` / `@x/y` 引用。另：GitHub compare API 对大文件不返回 patch，
    必须直接取 main 基线内容做本地 diff，不能只看 API patch。

22. **交互类回归三条（本轮实测）**：
    - 新增 / 启用 Fragment 分支（如 `case 5 -> SettingPersonalFragment`）必须同步 `mobile HomeActivity.onBackInvoked()` 的返回分支；漏了就会一路落到 `mManager.canBack(0)` → `super.onBackInvoked()` → `finish()`，表现为「个性设置按返回直接关掉 app」。返回分支要覆盖所有设置子页 position。
    - 自定义窗口尺寸的 Dialog（如 `AboutDialog`）必须在 `dialog.show()` **之前** `configureWindow()`，并显式 `setOnShowListener(d -> configureWindow(...))` 覆盖 `LightDialog.create` 的幂等回调；否则首帧先按 LightDialog 默认尺寸居中，show 后被改成本页尺寸，整窗「闪移」。此规则同样适用于任何绕过 `BaseAlertDialog` 直接用 `Dialog` 的弹窗。
    - 脚本 json 首次生成 / 新增条目必须按 `py -> js -> html -> url` 插入对应分组尾部（`insertPos()`），不能一律 `sites.add(...)` append——已有 url 条目时新 py 会排到 url 后面。同时已有条目的相对次序一律不动（用户改过 / 排过的 json 保持现状），`reset()` 不重排。

23. **在已有方法前插入代码时，不得改动原方法的声明（可见性 / static / 返回类型 / 参数）**。
    本轮为给 LocalScriptSource 加 insertPos/typeRank，用「替换 kindOf 声明行」的方式插入，
    new_string 里把 `public static String kindOf` 误写成 `private static`，CI 直接报
    `ScriptManageDialog.java:400 error: kindOf(JSONObject) has private access`。
    插入辅助方法一律用「锚点前追加」而非「替换原声明为 private」；改完必须全库扫一遍
    `LocalScriptSource.kindOf(` 之类的外部调用点，确认可见性没被降。任何对现有方法签名的改动，
    先 grep 调用点再提交，不要等 CI 编译来发现。

24. **代理总闸的三根线不许再退回「jar 自行判定」**：
    - 第 1 根线：`OkProxySelector.installAsDefault()` 在 `App.onCreate()` 调一次，
      进程内所有 `openConnection(null)` 都命中 `global`；`clear()` **不得**再
      `Authenticator.setDefault(null)`（selector 常驻后认证器要保留）。`system`
      构造时已存系统原默认，`fallback()` 不会递归。
    - 第 2 根线：`chaquo/ProxyEnv.apply()` 直接改写 CPython `os.environ` 的 6 个代理键，
      py 的 requests 立即生效；`Python.isStarted()` 未启动就跳过，由 `PyLoader.getSpider()`
      首次加载时再应用。**不要**改成往 ext 塞 proxy 字段、也不要清 spider 缓存重建。
    - js 走 quickjs `Connect` 里的 `OkHttp.client()`、播放走 `MediaSourceFactory` 的
      `OkHttp.player()`，二者构建时都挂了同一个 selector，天然跟随总闸，不要另加分支。
    - 第 3 根线（反射 jar 的 MenuProxy）只作实测漏网兜底，**不预置**，避免绑死混淆类名。
    - 口径不变：亮暗仍由 per-site 老逻辑判定、切站重新算、json 写回规则不动。

25. **脚本站点「工作区有、站点切换列表没有」的排查口径**：核心显示链
    （`VodConfig.initSite` 的 `Site.objectFrom().distinct()`、`SiteAdapter.addAll` 的
    `SiteBlockSetting.filter`、`SiteDialog`、`SiteAdapter`）在 main / OK影视+ / restore-upstream
    三个分支逐字节相同，差异不在这里；不要在没对比 tree 的情况下去改这些类。
    真正会丢站点的只有四处：① `Site.equals` 按 key 去重，多个空 / 重复 key 被吞成一条；
    ② `Site.objectFrom` 的 catch 返回 `new Site()`（空 key）；
    ③ `SiteBlockSetting.filter` 的 `site.isHide()` 与旧屏蔽记录；
    ④ 当前 home 不是脚本配置。
    统一在 `LocalScriptSource.buildJson()` 的 `parseSites()` 做「解析用副本规范化」：
    key 唯一、name 兜底、hide=0、ext/proxy/api/homePage 统一字符串，
    **磁盘 json 的 key / name / 次序 / sites_bac 一律不动**。同时把 in/out 条数写进日志，
    实机可对照工作区与站点列表条数。
    另：脚本管理第 5 行 `actionRow` 必须用 `weight=1` 左右撑满（固定 264dp 居中会让
    两侧留白、段标签被压成「工…区」），`addLabel()` 不得设 ellipsize + 固定 34dp。
    新增 url 也要走 `insertPos()`，否则 py/js/html/url 次序在手工录入时会破。

26. **本轮三条交互改动的约束，别再退回旧写法**：
    - html 站点切换：`HomeWebController.load()` 只要 `siteKey` 变化（或新站点没有
      homePage）就必须先 `closePage()` ——停加载、`about:blank` 替换文档、清历史/
      注入脚本、`onPause/onResume`，**不能只 `loadUrl(newUrl)` 复用、更不能只
      `setVisibility(GONE)`**（那样旧 html 的 JS/媒体/网络请求还在跑）。不销毁
      WebView：binding 仍持有 `homeWeb`，销毁后 `setVisibility` 会作用在废 view 上。
    - 退出确认：`ExitConfirmDialog` 与其 layout/drawable/color 放 **main**，两变体共用；
      mobile `HomeActivity.onBackInvoked()` 的最终分支必须弹确认，确认后才
      `finish()` / 后台播放。**不要**在 leanback/java 再留同名类（duplicate class）。
      手机宽度按 `screenWidth - 32dp` 封顶，TV 保持 420~560dp 原口径。
    - 首页顶部一行：`menu_vod.xml` 用 20dp 图标副本（`*_s.xml`，只改尺寸不改图形），
      `VodFragment.compactToolbar()` 固定 `minWidth=34dp / padding=4dp`，
      `contentInsetEnd=4dp`、logo 32dp/marginEnd 8dp。按最小 320dp 屏算：
      5×34=170dp + logo 40dp + 标题约 60dp ≈ 270dp ≤ toolbar 内容区 288dp。
      **固定 dp，不做按屏比例的智能缩放**；不动 toolbar 高度（homeWeb marginTop 对齐它）。

27. **本轮五条交互改动的约束，别退回旧写法**：
    - 默认壁纸主题是「雾蓝极简」= `WALL_DAYLIGHT_MINIMAL`(25)。`Setting.getWall()` 的
      Prefers 默认值、legacy 兜底、`nextDefaultWall()` 兜底 **三处**都要一致，
      只改一处会漏。默认只对首次安装 / 清数据生效，已保存过 wall 的用户要手动切一次。
    - 首页顶部：`menu_vod.xml` 常显 6 个（search/history/proxy/keep/menu_import_script/
      more_actions；`web_home_fullscreen` 默认 invisible 不占位）。`compactToolbar()` 固定
      **28dp 宽 / 2dp padding**（上一轮 34/4 空隙偏大），图标统一 20dp `*_s` 副本。
      6×28=168 + logo 40 + 标题 55 ≈ 263dp ≤ 288dp。标题 `android:textSize="15sp"`
      直接覆盖 `ToolbarTextAppearance` 的 20sp，不要改全局 style（别处也用）。
    - 退出确认：`ExitConfirmDialog` 必须继承 **BaseAlertDialog**（项目现成元素），
      用 `MaterialAlertDialogBuilder` 的标题 + 取消/确认按钮，**不要**自定义卡片布局
      和 `focusableInTouchMode="true"` 的 TextView（那是「取消要点两下」的根因）。
      默认焦点给 `android.R.id.button2`（取消），TV 上不误退出。
      `dialog_exit_confirm.xml` 与 5 个 shape/selector/color 已删除，不要再搬回来。

28. **实验室（lab）修复约束，别再退回上游 VodPlus 版的写法**：
    - 自启必须在 `App.startBackgroundServices()` 里、`Server.get().start()` **之后**调
      `LabAutoStart.start(this)`。放 onCreate 太早，命令里的 `{serverPort}` 会拿到 -1；
      不调则 `auto_install` / `auto_execute` 全是死开关。
    - 根目录**只认** `LabConfig.get().getRoot()`（默认 `/storage/emulated/0/WebHTV`，
      与 `lab_template.json` 的 `root` 一致）。禁止再写死
      `/storage/emulated/0/VodPlus/...`、`peekpro/...` 这些别的 app 的目录；
      `syncVodPlusAssets()` 是空实现（本分支不带 assets/VodPlus）。
    - **底部导航入口必须完整接线**（上轮曾误判「没有消费方」并切断，是错的）：
      `menu_nav.xml` 的 `R.id.lab`（图标 `ic_nav_lab` 已是 48dp，与其余 nav 图标同款）
      + `HomeActivity.setNavigation()` 里 `findItem(R.id.lab).setVisible(LabConfig.get().getNavEntry())`
      + `onNavigationItemSelected` 里 `LabActivity.start(this); return false`
      + `LabActivity` 设置弹窗的 `navEntryRow` 在手机端 VISIBLE、TV 端 GONE，并保存
      `setNavEntry()`。lab_strings.xml 的 `lab_nav_entry_title/hint` 就是给这个开关用的。
      改完开关要发 `ConfigEvent(COMMON)` 让首页 `setNavigation()` 重算。
    - 实验室的「全局代理」(默认 7890，注入 rootfs 进程环境变量) 与 app 的
      `OkProxySelector` 总闸 (默认 2080，管 OkHttp) 是两套独立体系，不要互相覆盖。
    - `LabConfig.serverPort()` 依赖 `Server.startManage()` / `Proxy.getPort()`，
      这条链路本分支完整，不要切断。

29. **MT「签名状态校验不通过（v1+v2+v3 都在）」的真正根因与修法**：
    - 不是缺 v1，也不是 META-INF 里混了第二套签名（那两个都有断言）。
    - 恢复分支引入 jgit / commons-compress / zxing 等依赖后，APK 的 META-INF 下
      会堆到 89~91 个条目：`androidx.*.*.version`、
      `com/android/build/gradle/app-metadata.properties`、
      `version-control-info.textproto`、`LICENSE`/`NOTICE` 等。这些条目**不被 v1
      签名覆盖**，apksigner 逐条报 `not protected by signature`，我们曾把它加进
      WARNING 白名单，MT 却据此判「签名状态校验不通过」。
    - 修法在 `.github/workflows/android-release.yml` 的
      `Clean META-INF metadata and realign before signing`：**签名前**删掉
      META-INF 下除 `services/` 与签名三件套外的所有条目，然后**必须**紧跟
      `zipalign -f -p 4`（`zip -d` 会重写 ZIP 破坏对齐）。顺序不能放到签名之后。
    - `META-INF/services/` 是 ServiceLoader 配置，**不能删**；它由 MANIFEST 覆盖，
      不会触发 not protected。
    - 签名后的 `Verify JAR (v1) integrity like MT` 必须硬断言
      「META-INF 除签名三件套与 services/ 外无残留」，并以 annotation 输出
      `not protected by signature` 的条数作为实证。
    - 别再靠「CI 全绿」推断「MT 通过」：CI 用的是 apksigner 口径，MT 更严。
      另外同包名不同证书（例如 `com.fongmi.android.oktv` 撞已装官方版）也会让
      MT 显示签名不一致、安装被系统拒绝，属设备侧冲突，不是 APK 签名坏了。
