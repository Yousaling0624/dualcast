import { describe, expect, it } from 'vitest'
import {
  followedLiveFromFollowing,
  followedLiveFromWebList,
  mergeFollowedRooms,
  normalizeFollowedRoom,
} from '../src/lib/followed'

describe('normalizeFollowedRoom', () => {
  it('兼容 room_id / roomid、area_v2_name / area_name_v2 两种字段', () => {
    expect(
      normalizeFollowedRoom({
        room_id: 101,
        uid: 7,
        uname: '主播甲',
        face: '//i0.hdslb.com/a.jpg',
        title: '今晚解说',
        live_status: 1,
        online: 1234,
        area_v2_name: '电竞赛事',
      })
    ).toEqual({
      roomId: 101,
      uid: 7,
      uname: '主播甲',
      face: 'https://i0.hdslb.com/a.jpg',
      title: '今晚解说',
      cover: '',
      liveStatus: 1,
      online: 1234,
      areaName: '电竞赛事',
    })

    expect(
      normalizeFollowedRoom({
        roomid: 202,
        uname: '主播乙',
        live_status: 1,
        area_name_v2: '主机游戏',
      })
    ).toMatchObject({ roomId: 202, areaName: '主机游戏' })
  })

  it('hit_ab 把标题 / 房间数字段清零时仍能取到房间号', () => {
    const room = normalizeFollowedRoom({
      room_id: 303,
      roomid: 0,
      uid: 8,
      uname: '主播丙',
      title: '',
      roomname: '备用房间名',
      live_status: 0,
    })
    expect(room).toMatchObject({
      roomId: 303,
      uname: '主播丙',
      title: '备用房间名',
      liveStatus: 1,
    })
  })

  it('没有房间号返回 null', () => {
    expect(normalizeFollowedRoom({ uname: '幽灵' })).toBeNull()
    expect(normalizeFollowedRoom(null)).toBeNull()
  })
})

describe('followedLiveFromWebList', () => {
  it('读取 rooms，未知直播状态按直播中处理，轮播保留 2', () => {
    const list = followedLiveFromWebList({
      rooms: [
        { room_id: 1, uname: 'A', live_status: 1 },
        { room_id: 2, uname: 'B', live_status: 0 },
        { room_id: 3, uname: 'C', live_status: 2 },
      ],
    })
    expect(list.map((r) => [r.roomId, r.liveStatus])).toEqual([
      [1, 1],
      [2, 1],
      [3, 2],
    ])
  })

  it('兼容 list 字段并按房间号去重', () => {
    const list = followedLiveFromWebList({
      list: [
        { roomid: 11, uname: 'A' },
        { roomid: 11, uname: 'A 重复' },
        { roomid: 12, uname: 'B' },
      ],
    })
    expect(list.map((r) => r.roomId)).toEqual([11, 12])
    expect(list[0].uname).toBe('A')
  })

  it('没有列表时返回空数组', () => {
    expect(followedLiveFromWebList(null)).toEqual([])
    expect(followedLiveFromWebList({})).toEqual([])
  })
})

describe('followedLiveFromFollowing', () => {
  it('只保留 live_status 为 1 / 2 的条目', () => {
    const list = followedLiveFromFollowing({
      list: [
        { roomid: 21, uname: 'A', live_status: 1 },
        { roomid: 22, uname: 'B', live_status: 0 },
        { roomid: 23, uname: 'C', live_status: 2 },
      ],
    })
    expect(list.map((r) => r.roomId)).toEqual([21, 23])
  })
})

describe('mergeFollowedRooms', () => {
  it('按房间号去重并保留第一次出现的顺序', () => {
    const list = mergeFollowedRooms([
      normalizeFollowedRoom({ roomid: 31, uname: 'A' })!,
      normalizeFollowedRoom({ roomid: 32, uname: 'B' })!,
      normalizeFollowedRoom({ roomid: 31, uname: 'A2' })!,
    ])
    expect(list.map((r) => r.uname)).toEqual(['A', 'B'])
  })
})
