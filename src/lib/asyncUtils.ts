export interface FileSignatureSource {
  lastModified?: number
  size?: number
}

export function getFileSignature(file: FileSignatureSource): string | null {
  if (typeof file.lastModified !== 'number' || typeof file.size !== 'number')
    return null

  return `${file.lastModified}:${file.size}`
}

export async function mapWithConcurrency<T, R>(
  items: T[],
  concurrency: number,
  mapper: (item: T, index: number) => Promise<R>,
): Promise<R[]> {
  if (items.length === 0)
    return []

  const results = new Array<R>(items.length)
  const runnerCount = Math.max(1, Math.min(concurrency, items.length))
  let cursor = 0

  const workers = Array.from({ length: runnerCount }, async () => {
    while (true) {
      const index = cursor
      cursor += 1

      if (index >= items.length)
        return

      results[index] = await mapper(items[index] as T, index)
    }
  })

  await Promise.all(workers)
  return results
}
