export type ErrorCode =
  | 'INVALID_INPUT'
  | 'UNAUTHORIZED'
  | 'TOKEN_EXPIRED'
  | 'SESSION_EXPIRED'
  | 'MFA_INVALID'
  | 'FORBIDDEN'
  | 'KYC_REQUIRED'
  | 'ACCOUNT_SUSPENDED'
  | 'PASSWORD_CHANGE_REQUIRED'
  | 'NOT_FOUND'
  | 'CONFLICT'
  | 'RATE_CHANGED'
  | 'INSUFFICIENT_SHARES'
  | 'SALE_CLOSED'
  | 'TOO_MANY_PENDING'
  | 'PAYLOAD_TOO_LARGE'
  | 'LOCKED'
  | 'TOO_MANY_REQUESTS'
  | 'SERVICE_UNAVAILABLE'
  | 'SERVER_ERROR';

const STATUS: Record<ErrorCode, number> = {
  INVALID_INPUT: 400,
  UNAUTHORIZED: 401,
  TOKEN_EXPIRED: 401,
  SESSION_EXPIRED: 401,
  MFA_INVALID: 401,
  FORBIDDEN: 403,
  KYC_REQUIRED: 403,
  ACCOUNT_SUSPENDED: 403,
  PASSWORD_CHANGE_REQUIRED: 403,
  NOT_FOUND: 404,
  CONFLICT: 409,
  RATE_CHANGED: 409,
  INSUFFICIENT_SHARES: 409,
  SALE_CLOSED: 409,
  TOO_MANY_PENDING: 409,
  PAYLOAD_TOO_LARGE: 413,
  LOCKED: 423,
  TOO_MANY_REQUESTS: 429,
  SERVICE_UNAVAILABLE: 503,
  SERVER_ERROR: 500,
};

const DEFAULT_MESSAGE: Record<ErrorCode, string> = {
  INVALID_INPUT: 'তথ্যগুলো সঠিকভাবে পূরণ করুন।',
  UNAUTHORIZED: 'অনুমতি নেই। আবার লগইন করুন।',
  TOKEN_EXPIRED: 'সেশনের মেয়াদ শেষ।',
  SESSION_EXPIRED: 'সেশনের মেয়াদ শেষ হয়েছে। আবার লগইন করুন।',
  MFA_INVALID: 'OTP সঠিক নয় বা মেয়াদ শেষ।',
  FORBIDDEN: 'এই কাজের অনুমতি আপনার নেই।',
  KYC_REQUIRED: 'শেয়ার কিনতে আগে KYC যাচাই সম্পন্ন হতে হবে।',
  ACCOUNT_SUSPENDED: 'আপনার অ্যাকাউন্ট স্থগিত করা হয়েছে। অফিসে যোগাযোগ করুন।',
  PASSWORD_CHANGE_REQUIRED: 'চালিয়ে যেতে আগে পাসওয়ার্ড পরিবর্তন করুন।',
  NOT_FOUND: 'তথ্য পাওয়া যায়নি।',
  CONFLICT: 'তথ্যটি আগে থেকেই আছে বা বর্তমান অবস্থায় এটি করা যাবে না।',
  RATE_CHANGED: 'শেয়ারের রেট পরিবর্তন হয়েছে। নতুন রেট দেখে আবার নিশ্চিত করুন।',
  INSUFFICIENT_SHARES: 'পর্যাপ্ত শেয়ার অবশিষ্ট নেই।',
  SALE_CLOSED: 'এই মুহূর্তে শেয়ার বিক্রি বন্ধ আছে।',
  TOO_MANY_PENDING: 'আপনার ৩টি অর্ডার যাচাইয়ের অপেক্ষায় আছে। আগে সেগুলো নিষ্পত্তি হোক।',
  PAYLOAD_TOO_LARGE: 'ফাইল বা তথ্য অনেক বড়। প্রতিটি ছবি সর্বোচ্চ ৫ MB হতে পারে।',
  LOCKED: 'বারবার ভুল চেষ্টার কারণে অ্যাকাউন্ট সাময়িকভাবে লক করা হয়েছে। কিছুক্ষণ পরে চেষ্টা করুন।',
  TOO_MANY_REQUESTS: 'অনেক বেশি অনুরোধ। কিছুক্ষণ পরে আবার চেষ্টা করুন।',
  SERVICE_UNAVAILABLE: 'এই সেবাটি এখন চালু নেই। পরে আবার চেষ্টা করুন।',
  SERVER_ERROR: 'সার্ভারে সমস্যা হয়েছে। কিছুক্ষণ পরে আবার চেষ্টা করুন।',
};

export class ApiError extends Error {
  readonly code: ErrorCode;
  readonly status: number;
  readonly fields?: Record<string, string>;

  constructor(code: ErrorCode, message?: string, fields?: Record<string, string>) {
    super(message ?? DEFAULT_MESSAGE[code]);
    this.code = code;
    this.status = STATUS[code];
    if (fields && Object.keys(fields).length > 0) this.fields = fields;
  }

  toBody() {
    return {
      error: {
        code: this.code,
        message: this.message,
        ...(this.fields ? { fields: this.fields } : {}),
      },
    };
  }
}

export function errorBody(code: ErrorCode, message?: string, fields?: Record<string, string>) {
  return new ApiError(code, message, fields).toBody();
}

export function statusOf(code: ErrorCode): number {
  return STATUS[code];
}
