export const KINDS = {
  pdf: { label: 'PDF', color: '#e5383b', exts: ['pdf'] },
  word: { label: 'Word', color: '#2b6de8', exts: ['docx'] },
  excel: { label: 'Excel', color: '#1f9d55', exts: ['xlsx', 'xls', 'csv', 'ods'] },
  text: { label: 'Текст', color: '#7a52d1', exts: ['txt', 'md', 'json', 'log', 'xml', 'html'] },
};

export function kindOf(name) {
  const ext = name.split('.').pop().toLowerCase();
  return Object.keys(KINDS).find((k) => KINDS[k].exts.includes(ext)) || null;
}

export const ACCEPT = Object.values(KINDS).flatMap((k) => k.exts.map((e) => '.' + e)).join(',');

export function formatSize(b) {
  if (b < 1024) return b + ' Б';
  if (b < 1024 * 1024) return (b / 1024).toFixed(1) + ' КБ';
  return (b / 1024 / 1024).toFixed(1) + ' МБ';
}
