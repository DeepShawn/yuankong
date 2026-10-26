#ifndef LANCMD_COMMAND_LINE_H
#define LANCMD_COMMAND_LINE_H

#include <stddef.h>
#include <wchar.h>

/* 按 Windows CRT 的 argv 规则引用每个参数。引号前的反斜线需翻倍，
 * 末尾反斜线也需翻倍，以免吃掉闭合引号。首参数前不能有空格：
 * 否则 CRT 会把 argv[0] 解析为空串，Python 将解释器自身当成脚本。
 * 先计算长度，空间不足时不改变输出，由调用方明确报错。
 */
static int append_arg(wchar_t *out, size_t cap, const wchar_t *arg)
{
    size_t used = wcslen(out);
    size_t needed = (used ? 1 : 0) + 2;
    size_t slashes = 0;
    const wchar_t *p;

    for (p = arg; *p; ++p) {
        if (*p == L'\\') {
            ++slashes;
        } else {
            needed += (*p == L'"') ? slashes * 2 + 2 : slashes + 1;
            slashes = 0;
        }
    }
    needed += slashes * 2;
    if (used >= cap || needed >= cap - used) return 0;

    if (used) out[used++] = L' ';
    out[used++] = L'"';
    slashes = 0;
    for (p = arg; *p; ++p) {
        if (*p == L'\\') {
            ++slashes;
            continue;
        }
        size_t count = (*p == L'"') ? slashes * 2 + 1 : slashes;
        while (count--) out[used++] = L'\\';
        out[used++] = *p;
        slashes = 0;
    }
    slashes *= 2;
    while (slashes--) out[used++] = L'\\';
    out[used++] = L'"';
    out[used] = L'\0';
    return 1;
}

#endif
