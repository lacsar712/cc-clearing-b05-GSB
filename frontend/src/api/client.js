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
  (error) => {
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

export default api

// Server-side CSV export download: hits the dedicated export endpoint with the
// current query params and saves the returned blob as a file. Read-only, so it
// is available to viewer accounts as well.
export async function downloadCsv(url, params, filename) {
  const resp = await api.get(url, { params, responseType: 'blob' })
  const blobUrl = URL.createObjectURL(new Blob([resp.data], { type: 'text/csv;charset=UTF-8' }))
  const a = document.createElement('a')
  a.href = blobUrl
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(blobUrl)
}
