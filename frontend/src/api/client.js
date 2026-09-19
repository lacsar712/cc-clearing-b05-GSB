import axios from 'axios'
import { ElMessage } from 'element-plus'

const api = axios.create({
  baseURL: '/api',
  timeout: 30000
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

api.interceptors.response.use(
  (resp) => resp,
  async (error) => {
    // 下载（blob）请求失败时，响应体是 Blob，需解回 JSON 才能取到错误信息
    if (error.config?.responseType === 'blob' && error.response?.data instanceof Blob) {
      try {
        error.response.data = JSON.parse(await error.response.data.text())
      } catch {
        // 保留原始错误体
      }
    }
    const payload = error.response?.data
    const msg = payload?.message || error.message || '请求失败'
    if (error.response?.status === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('username')
      localStorage.removeItem('role')
      if (!window.location.pathname.includes('/login')) {
        window.location.href = '/login'
      }
    } else {
      ElMessage.error(msg)
    }
    return Promise.reject(error)
  }
)

/**
 * 调用服务端下载接口并保存文件。携带 JWT，文件名取 Content-Disposition。
 * 所有数据都来自服务端返回的文件，前端不拼任何行数据。
 */
export async function downloadFile(url, params) {
  const { data, headers } = await api.get(url, { params, responseType: 'blob' })
  const disposition = headers['content-disposition'] || ''
  const utf8Match = disposition.match(/filename\*=UTF-8''([^;]+)/i)
  const asciiMatch = disposition.match(/filename="([^"]+)"/i)
  const filename = utf8Match
    ? decodeURIComponent(utf8Match[1])
    : asciiMatch
      ? decodeURIComponent(asciiMatch[1])
      : 'export.csv'
  const link = document.createElement('a')
  link.href = URL.createObjectURL(data)
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(link.href)
  return data
}

export default api
