"""Locate comment spans in Kotlin, Swift, TypeScript and JavaScript source.

Comment-only editing is what makes a cleanup provably behaviour-preserving, so
this walker has to be right about the things that are NOT comments: string
literals, raw/multiline strings, character literals, and escapes. Kotlin and
Swift both allow NESTED block comments, so depth is tracked rather than
scanning for the first close marker.
"""


def comment_spans(src: str, ext: str):
    i, n = 0, len(src)
    spans = []
    nested = ext in ('kt', 'swift')
    while i < n:
        c = src[i]
        two = src[i:i + 2]
        if two == '//':
            j = src.find('\n', i)
            j = n if j < 0 else j
            spans.append((i, j))
            i = j
            continue
        if two == '/*':
            depth, j = 1, i + 2
            while j < n and depth:
                t = src[j:j + 2]
                if nested and t == '/*':
                    depth += 1
                    j += 2
                elif t == '*/':
                    depth -= 1
                    j += 2
                else:
                    j += 1
            spans.append((i, j))
            i = j
            continue
        if src[i:i + 3] == '"""':
            j = src.find('"""', i + 3)
            i = n if j < 0 else j + 3
            continue
        if c == '"' or c == "'" or c == '`':
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == c:
                    j += 1
                    break
                if src[j] == '\n' and c != '`':
                    break
                j += 1
            i = j
            continue
        i += 1
    return spans


def strip_comments(src: str, ext: str) -> str:
    """The code with every comment byte replaced by a space.

    Spaces rather than deletion so offsets are preserved: two strippings can be
    compared directly, and a rule that touched code shows up as a difference.
    """
    out = list(src)
    for a, b in comment_spans(src, ext):
        for k in range(a, b):
            if out[k] != '\n':
                out[k] = ' '
    return ''.join(out)
