package com.dualcast.live.data.model

/**
 * 弹幕文案拼装。
 *
 * B 站没有「楼中楼」回复，斗鱼那种回复在 B 站只能靠文本表达：
 *
 *     {回复内容}@{被回复人}:{被回复人发的弹幕}
 *
 * 例如「确实@张三:这波太秀了」。
 *
 * B 站单条弹幕上限 20 字，所以这里必须算着长度拼：
 * 先保住回复内容，再把对方原话截断（用省略号），最后整体兜底截断。
 * 昵称太长也会截到 12 字，否则光 @ 就把额度吃光了。
 */
object DanmakuText {

    /** B 站单条弹幕的字数上限，超了服务端直接 10030。 */
    const val LIMIT = 20

    /** @ 后面最多留这么长的昵称。 */
    private const val NAME_LIMIT = 12

    /** 拼完回复内容后，至少给「对方原话」留几个字。 */
    private const val QUOTE_RESERVE = 3

    fun replyName(target: DanmakuMessage): String =
        target.user.trim().ifBlank { "TA" }.take(NAME_LIMIT)

    /** 输入框最多让用户打多少字，保证 @昵称: 之后还有一点原话。 */
    fun replyInputLimit(target: DanmakuMessage): Int {
        val budget = LIMIT - replyName(target).length - 2
        return (budget - QUOTE_RESERVE).coerceAtLeast(1)
    }

    fun formatReply(reply: String, target: DanmakuMessage): String {
        val name = replyName(target)
        val budget = (LIMIT - name.length - 2).coerceAtLeast(1)
        val replyCap = (budget - QUOTE_RESERVE).coerceAtLeast(1)
        val body = reply.trim().replace('\n', ' ').replace('\r', ' ').take(replyCap)
        val quoteRoom = budget - body.length
        val quote = target.text.trim().replace('\n', ' ').replace('\r', ' ')
        val shortened = when {
            quoteRoom <= 0 -> ""
            quote.length <= quoteRoom -> quote
            quoteRoom == 1 -> "…"
            else -> quote.take(quoteRoom - 1) + "…"
        }
        return "$body@$name:$shortened".take(LIMIT)
    }
}
