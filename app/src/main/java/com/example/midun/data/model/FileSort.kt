package com.example.midun.data.model

import java.text.Collator
import java.util.Locale

/**
 * 排序依据。**只是「按什么排」，不含方向**——方向是 [FileSort.descending] 那一半。
 *
 * 拆成两件事是照 Windows 资源管理器那套（客户 2026-08-14）：右键菜单上半选「名称/日期/大小」，
 * 下半单独选「递增/递减」。四选一的写法（时间正/时间反/名称正/名称反）在选项一多就会翻倍，
 * 而用户脑子里本来就是两个独立的决定。
 */
enum class SortField(val label: String) {
    TIME("时间"),
    NAME("名称")
}

/** 列表排序 = 依据 + 方向。 */
data class FileSort(val field: SortField, val descending: Boolean) {

    companion object {
        /**
         * 文件的 `createdAt` 来自 `RealFileSystem.getFilesInFolder` 逐个 `fileMeta` 从卡上读出来的时间。
         * 默认「时间 · 递减」＝刚导入/刚收到的排最上面，最常见的诉求。
         */
        val FILE_FIELDS = listOf(SortField.TIME, SortField.NAME)
        val FILE_DEFAULT = FileSort(SortField.TIME, descending = true)

        /**
         * 文件夹同样两种可选。**时间这一项是 2026-08-14 才成立的**：在那之前 `RealFileSystem.getFolders`
         * 根本没读卡、`createdAt` 用的是 `FileItem` 的默认值＝每次刷新列表的此刻，给「按时间」等于骗人
         * （排出来实为名称兜底排）。现由 `RealFileSystem.dirCreateTime` 经 `SFOpenAsDir` + `SFGetTime` 真读，
         * 读不到则为 0、UI 显示 `--`。
         *
         * ⚠️ **待真机验证**：SDK 里没有任何示例在目录句柄上调过 `SFGetTime`（见 dirCreateTime 的说明）。
         * 若真卡上全读不出来（文件夹日期一律 `--`、按时间排等于按名称排），把 [SortField.TIME] 从本表删掉
         * 即可退回「文件夹只能按名称排」，其余代码不必动。
         *
         * 默认仍是名称递增：文件夹是导航结构，名字不变顺序就不变，用户「我那个夹子在第二个」的位置记忆才成立。
         */
        // 2026-08-15 暂时收回 TIME：`RealFileSystem.READ_FOLDER_TIME` 关着时文件夹没有时间可排，
        // 留着这个选项等于骗人（排出来实为名称兜底排）。那个开关一旦验证可用，把 TIME 加回来即可。
        val FOLDER_FIELDS = listOf(SortField.NAME)
        val FOLDER_DEFAULT = FileSort(SortField.NAME, descending = false)

        /**
         * 按名字取依据；不认识（旧版本写的、或人为改坏的）退回 [fallback]。
         *
         * **存枚举名而不是序号**：以后往 [SortField] 中间插一项，序号会把老用户的偏好静默改成另一种。
         */
        fun fieldOf(name: String?, fallback: SortField): SortField =
            SortField.entries.firstOrNull { it.name == name } ?: fallback
    }
}

/**
 * 名称排序用中文排序规则（[Collator]），不是 `String.compareTo`。
 *
 * 后者比的是 UTF-16 码点：中文按 Unicode 码位排出来的顺序对用户毫无意义（「张」在「李」前面纯属偶然），
 * 数字也会出现 "10" 排在 "2" 前面。`Collator` 至少把中文按拼音、英文按字母、大小写等价处理。
 *
 * 建一次复用：`Collator.getInstance` 每次都会新建实例并读区域数据，排一屏文件要调上百次比较。
 * 它不是线程安全的，但排序只在 UI 线程做，够用。
 */
private val nameCollator: Collator = Collator.getInstance(Locale.CHINA)

/**
 * 按 [sort] 排一份新列表。原列表不动（数据源那份要保持卡上原始顺序，切换排序才能来回切）。
 *
 * 名称、id 依次兜底，保证顺序是**确定的**——否则同一批同秒导入的文件每次重组都可能换个次序，看起来像在乱跳。
 * 递减是把整个比较器（含兜底）反过来，不只反主键：同名同秒的两个文件在两个方向下互为镜像，
 * 用户切来切去看到的是同一张表倒过来，而不是「有几行没跟着动」。
 */
fun List<FileItem>.applySort(sort: FileSort): List<FileItem> {
    val byName = compareBy<FileItem, String>(nameCollator) { it.name }.thenBy { it.id }
    val comparator = when (sort.field) {
        SortField.TIME -> compareBy<FileItem> { it.createdAt }.then(byName)
        SortField.NAME -> byName
    }
    return sortedWith(if (sort.descending) comparator.reversed() else comparator)
}
