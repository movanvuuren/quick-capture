export function escapeHtml(value: string) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function renderInlineMarkdown(value: string) {
  const inline = escapeHtml(value)
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/~~(.+?)~~/g, '<s>$1</s>')
    .replace(/\*(.+?)\*/g, '<em>$1</em>')
    .replace(/_(.+?)_/g, '<em>$1</em>')
    .replace(/`(.+?)`/g, '<code>$1</code>')

  return inline.replace(/(^|\s)#([A-Za-z][\w-]{1,24})\b/g, (_m, lead, tag) => {
    let hash = 0
    for (let i = 0; i < tag.length; i++)
      hash = ((hash << 5) - hash + tag.charCodeAt(i)) | 0

    const hue = Math.abs(hash) % 360
    return `${lead}<span class="tag-chip" style="--tag-h:${hue}">#${tag}</span>`
  })
}

export function renderNotePreviewMarkdown(markdown: string) {
  const lines = markdown
    .replace(/\r\n/g, '\n')
    .split('\n')
    .map(line => line.trim())
    .filter(line => line.length > 0)
    .slice(0, 4)

  if (lines.length === 0)
    return '<span class="empty">No preview available.</span>'

  return lines.map((line) => {
    const heading = line.match(/^#{1,3}\s+(.+)$/)
    if (heading)
      return `<strong>${renderInlineMarkdown(heading[1] || '')}</strong>`

    const checkbox = line.match(/^-\s\[([ xX])\]\s+(.+)$/)
    if (checkbox)
      return `${(checkbox[1] || '').toLowerCase() === 'x' ? '☑' : '☐'} ${renderInlineMarkdown(checkbox[2] || '')}`

    const bullet = line.match(/^[-*+]\s+(.+)$/)
    if (bullet)
      return `• ${renderInlineMarkdown(bullet[1] || '')}`

    const ordered = line.match(/^(\d+)\.\s+(.+)$/)
    if (ordered)
      return `${ordered[1]}. ${renderInlineMarkdown(ordered[2] || '')}`

    return renderInlineMarkdown(line)
  }).join('<br>')
}
