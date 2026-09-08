package com.boomsset.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.boomsset.ui.LocalGainLossColors
import com.boomsset.ui.gainLossColorsFor

/**
 * 旺资的品牌配色 —— **暖金：中明度、饱和的琥珀金**。
 *
 * 全部色值由 OKLCH 三元组解出，**唯一的输入是色相角 H = 70°（古金）**。
 * 表面、容器、文字、图标共用这一个 H，所以换品牌色时整套跟着走。
 *
 * ## 色值的三次改版（当前是 `#955E00`）
 *
 * `#BD4D03`（彩度 0.160）反馈"不够高级" —— 问题不在色相，在**角色分配**：
 * 高彩度橙坐在 `primary` 上做大面积填充，在中文 App 语境里就是电商那一档的读感。
 * 于是压到莫兰迪区间 `#918163`（彩度 **0.047**）。
 *
 * 但那一版反馈"太沉闷"。**低彩度换来的克制，代价是所有靠颜色表示状态的地方都变弱**
 * （底部导航的选中态就因此比未选中还淡，见 App.kt）。于是彩度回到 0.120、
 * 亮度压到 L 0.565（`#986E00`，色相 82°）—— 关键在于**往深走反而能放更多彩度**，
 * 因为「权益类」橙坐在 L 0.715，离得远了 ΔE 自然拉开。
 *
 * `#986E00` 仍被反馈"不好看"，问题是色相 82° 偏黄绿、读起来像芥末。
 * 现在移到 **70°（古金）**，同时把彩度稍降到 0.115、亮度降到 L 0.530 ——
 * ΔE 反而更宽（**18.9**，不再贴着 15 的门槛），白字 5.41:1 也更稳。
 *
 * ⚠️ **"浅而透亮的香槟金"当 primary 是不可能的，别再试。**
 * 卡住它的**不是**撞色规则，是下面第 2 条「对页面底 ≥3:1」——
 * 低于它 FAB 会糊进背景，而**同一个 primary 还在画净值柱，柱子是数据标记**。
 * 这把 primary 的亮度封在 L ≤ 0.60，可用的金全在"古铜/琥珀"这一档。
 * 想要的那种浅金现在住在 `primaryContainer`（净值 hero 卡片）上。
 * 另：把撞色门槛从 15 放宽到 12 试过，只多出 L 0.57→0.60，还要把白字换回深棕，
 * **不值得为它破例**。
 *
 * 中间试过"深墨锚定"（primary L 0.33、深色 hero 卡片），反馈是**太暗**；
 * 又试过冷色，但冷色区被四个大类色在亮度轴上占满了（保障类紫 L.48、
 * 流动资金蓝 L.60、固定收益绿和另类实物青 L.70），冷色 primary 最亮只能到
 * **L 0.42**，必然比暖色深一档 —— 这是选暖色的实际理由，不是审美偏好。
 *
 * ## 三条不能动的判据（改色值要重新验）
 *
 * 1. **与五个大类图表色的最小 ΔE ≥ 15**（OKLab ×100，正常视力）。实测 **18.9**。
 *    配置页上 FAB 和权益类那根条会挨在一起，必须分得开。
 *    ⚠️ 这条是**从图表配色外推**来的（dataviz 那个门槛本来是给同一套分类色的
 *    相邻两色定的，FAB 不是数据标记），所以它可议 —— 但算过，放宽几乎无收益。
 * 2. **primary 对页面底 ≥ 3:1** —— 低于它 FAB 会糊进背景，净值柱也会变淡。
 *    实测 5.17:1。**这才是真正卡住亮度的那一条。**
 * 3. **onPrimary 对 primary ≥ 4.5:1**。实测白字 5.41:1。
 *
 * ## 中性面不是纯灰
 *
 * 表面和文字都带 H=70 的微量彩度（近白面 0.008、中灰 0.013、深色文字 0.020）——
 * 纯 `#FFFFFF/#F5F5F5` 是"正确但临床"，微染才是"贵"的来源。
 * 上一版用的是有知有行的纯中性梯度，这一版把亮度阶保留、只加彩度，
 * 所以正文对比度几乎没变（13.1:1）。
 *
 * ⚠️ **这些色值和 `tools/appicon/generate.py` 的 `BRAND_HUE` 必须一致**，
 * 改配色两边一起改，改完跑那个脚本重新生成图标。
 *
 * **必须逐个角色写全，不能只覆盖 primary。** `lightColorScheme()` 没传的参数会取
 * 基线默认值，而基线的 surface 家族是**带紫调**的灰 —— 只改 primary 的话
 * Card 和 BottomBar 仍然发紫，看起来像换了一半。
 *
 * 品牌色和**涨跌语义色是两件事**，后者见 [com.boomsset.ui.GainLossColors]。
 */
private val BrandCream = Color(0xFFD3BC7D)

private val LightScheme = lightColorScheme(
    primary = BrandCream,
    // 白色 —— 5.41:1，过正文门槛。⚠️ 这一条**和亮度强耦合**：
    // 更早那版 primary 亮到 L 0.61 时白字只有 3.86:1、不合格，只能用深棕。
    // 改 primary 的亮度必须重算这里。
    onPrimary = Color(0xFF1D1500),
    // 净值 hero 卡片底 —— **奶黄**。
    // 这里是整套配色里唯一能放"浅而透亮的金"的地方：大面积浅色底配深色字，
    // 不受"对页面底 ≥3:1"约束（那条只管 primary 这种要被看见的小元件）。
    // 反馈想要"高级金"而 primary 做不到，就放在这里。
    primaryContainer = Color(0xFFF0E3BE),
    onPrimaryContainer = Color(0xFF352800),
    inversePrimary = Color(0xFFC7AF6D),

    // 次要色走同色相的低彩中性 —— 只留一个强调色，其余全部近中性
    secondary = Color(0xFF70675E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF1ECE6),
    onSecondaryContainer = Color(0xFF342C23),

    // 第三色刻意留在同色相内，**不用任何分类色的色相** ——
    // 否则它会和某个大类的颜色撞车，让读者以为两者有关
    tertiary = Color(0xFF6B5924),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFECDDB5),
    onTertiaryContainer = Color(0xFF322600),

    // error 和「超配」是两回事，色值也不同（超配是 #C5453F）
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    background = Color(0xFFFEF9F4),
    onBackground = Color(0xFF30271D),
    surface = Color(0xFFFEF9F4),
    onSurface = Color(0xFF30271D),
    surfaceVariant = Color(0xFFEBE6E2),
    onSurfaceVariant = Color(0xFF6C6359),
    surfaceTint = BrandCream,
    inverseSurface = Color(0xFF30271D),
    inverseOnSurface = Color(0xFFF6F2ED),

    surfaceDim = Color(0xFFE5E1DC),
    surfaceBright = Color(0xFFFFFCF8),
    surfaceContainerLowest = Color(0xFFFFFEFD),
    surfaceContainerLow = Color(0xFFFAF6F1),
    surfaceContainer = Color(0xFFF6F2ED),
    surfaceContainerHigh = Color(0xFFF0ECE7),
    surfaceContainerHighest = Color(0xFFEBE6E2),

    outline = Color(0xFF9A9187),
    outlineVariant = Color(0xFFE0D8CE),
    scrim = Color(0xFF000000),
)

/**
 * 深色模式是**另选的一组步进**，不是浅色的自动翻转 —— 同一个色相角，
 * 按深底重新取亮度并单独验过（primary 对底 9.74:1，与深色大类色最小 ΔE 15.1）。
 */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFE0B310),
    onPrimary = Color(0xFF281E00),
    primaryContainer = Color(0xFF57450C),
    onPrimaryContainer = Color(0xFFECDDB5),
    inversePrimary = BrandCream,

    secondary = Color(0xFFC5BCB3),
    onSecondary = Color(0xFF312A22),
    secondaryContainer = Color(0xFF413C36),
    onSecondaryContainer = Color(0xFFE6DED6),

    tertiary = Color(0xFFC7B482),
    onTertiary = Color(0xFF2B2000),
    tertiaryContainer = Color(0xFF503F03),
    onTertiaryContainer = Color(0xFFE9DAB2),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),

    background = Color(0xFF16120D),
    onBackground = Color(0xFFECE5DC),
    surface = Color(0xFF16120D),
    onSurface = Color(0xFFECE5DC),
    surfaceVariant = Color(0xFF3D3833),
    onSurfaceVariant = Color(0xFFC5BCB3),
    surfaceTint = Color(0xFFE0B310),
    inverseSurface = Color(0xFFECE5DC),
    inverseOnSurface = Color(0xFF30271D),

    surfaceDim = Color(0xFF16120D),
    surfaceBright = Color(0xFF433D38),
    surfaceContainerLowest = Color(0xFF100B07),
    surfaceContainerLow = Color(0xFF1D1914),
    surfaceContainer = Color(0xFF211C17),
    surfaceContainerHigh = Color(0xFF2E2924),
    surfaceContainerHighest = Color(0xFF39342F),

    outline = Color(0xFF8A8279),
    outlineVariant = Color(0xFF3F3830),
    scrim = Color(0xFF000000),
)

/**
 * 两端共用的主题入口。跟随系统深浅色 —— 之前是恒亮，深色模式下白得刺眼。
 */
@Composable
fun BoomssetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // 状态栏图标要跟着主题反色 —— 见 ApplySystemBarsAppearance 的注释，
    // 不设的话浅色主题下状态栏是白字压白底
    ApplySystemBarsAppearance(darkTheme)

    // 图表配色和涨跌色都用**同一个** darkTheme —— 让它们自己去读系统深浅色
    // 会和主题不一致（预览和测试里尤其容易出现）
    CompositionLocalProvider(
        LocalChartColors provides chartColorsFor(darkTheme),
        LocalGainLossColors provides gainLossColorsFor(darkTheme),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            content = content,
        )
    }
}
