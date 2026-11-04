/**
 * 「关注的主播」数据结构与接口返回解析。
 *
 * 两个接口的数据形状不完全一样：
 * - xfetter/GetWebList：只返回正在直播的关注主播，一次拿全；
 * - web-ucenter/user/following：分页返回全部关注，需要自己筛 live_status。
 *
 * GetWebList 在 hit_ab=true 时会把 online / cover / area 等字段清零，
 * 但 room_id / uid / uname / face / title 仍在，这里做兼容。
 */

export interface FollowedLiveRoom {
  roomId: number
  uid: number
  uname: string
  face: string
  title: string
  cover: string
  liveStatus: number
  online: number
  areaName: string
}

function num(value: unknown): number {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}

function str(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

function normUrl(u: string): string {
  if (!u) return ''
  if (u.startsWith('//')) return `https:${u}`
  if (u.startsWith('http://')) return u.replace(/^http:\/\//, 'https://')
  return u
}

/** 把 GetWebList / following 的一条原始数据转成统一结构；没有房间号时返回 null */
export function normalizeFollowedRoom(raw: any): FollowedLiveRoom | null {
  const roomId = num(raw?.room_id) || num(raw?.roomid)
  if (!roomId) return null
  const status = num(raw?.live_status)
  return {
    roomId,
    uid: num(raw?.uid),
    uname: str(raw?.uname) || str(raw?.nickname),
    face: normUrl(str(raw?.face)),
    title: str(raw?.title) || str(raw?.roomname) || `房间 ${roomId}`,
    cover: normUrl(str(raw?.cover_from_user) || str(raw?.room_cover) || str(raw?.keyframe)),
    // 缺省时按直播中处理：GetWebList 只会返回正在直播的房间
    liveStatus: status === 2 ? 2 : 1,
    online: num(raw?.online),
    areaName: str(raw?.area_v2_name) || str(raw?.area_name_v2) || str(raw?.area_name),
  }
}

/** 按房间号去重，保留第一次出现的顺序 */
export function mergeFollowedRooms(list: FollowedLiveRoom[]): FollowedLiveRoom[] {
  const seen = new Set<number>()
  return list.filter((room) => {
    if (seen.has(room.roomId)) return false
    seen.add(room.roomId)
    return true
  })
}

function pickRooms(raw: any[]): FollowedLiveRoom[] {
  return mergeFollowedRooms(
    raw
      .map(normalizeFollowedRoom)
      .filter((room): room is FollowedLiveRoom => room !== null)
  )
}

/** xfetter/GetWebList 的 data：rooms / list 二选一，全部都是正在直播的 */
export function followedLiveFromWebList(data: any): FollowedLiveRoom[] {
  const raw = Array.isArray(data?.rooms)
    ? data.rooms
    : Array.isArray(data?.list)
      ? data.list
      : []
  return pickRooms(raw)
}

/** web-ucenter/user/following 的 data：只保留 live_status 为 1 / 2 的条目 */
export function followedLiveFromFollowing(data: any): FollowedLiveRoom[] {
  const raw: any[] = Array.isArray(data?.list) ? data.list : []
  return pickRooms(
    raw.filter((item) => {
      const status = num(item?.live_status)
      return status === 1 || status === 2
    })
  )
}
