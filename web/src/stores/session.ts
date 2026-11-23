/**
 * 会话级状态：联调身份（纯电商版，不依赖音乐能力探测）。
 */
import { ref } from 'vue'
import {
  ALLOW_DEV_IDENTITY,
  getUserId,
  resetDevUserId,
  setDevUserId,
} from '@/api/identity'

const userId = ref(getUserId())

export const session = {
  userId,
  allowDevIdentity: ALLOW_DEV_IDENTITY,

  updateUserId(next: string): boolean {
    const ok = setDevUserId(next)
    if (ok) {
      userId.value = getUserId()
    }
    return ok
  },

  resetUserId(): void {
    resetDevUserId()
    userId.value = getUserId()
  },
}
