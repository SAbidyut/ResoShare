import type { FastifyRequest } from 'fastify';
import sjson from 'secure-json-parse';
import { ApiError } from '../errors.js';

export type ParsedMultipart = { data: unknown; files: Record<string, Buffer> };

/** Reads a multipart body with exactly one JSON `data` field and the allowed file fields (each at most once). */
export async function readMultipart(req: FastifyRequest, allowedFiles: string[]): Promise<ParsedMultipart> {
  if (!req.isMultipart()) throw new ApiError('INVALID_INPUT', 'ফর্মটি সঠিকভাবে পাঠানো হয়নি।');
  let data: unknown = undefined;
  let dataSeen = false;
  const files: Record<string, Buffer> = {};
  for await (const part of req.parts()) {
    if (part.type === 'file') {
      if (!allowedFiles.includes(part.fieldname) || files[part.fieldname]) {
        part.file.resume();
        throw new ApiError('INVALID_INPUT', 'অপ্রত্যাশিত ফাইল পাঠানো হয়েছে।');
      }
      files[part.fieldname] = await part.toBuffer();
    } else {
      if (part.fieldname !== 'data' || dataSeen || typeof part.value !== 'string') {
        throw new ApiError('INVALID_INPUT', 'ফর্মটি সঠিকভাবে পাঠানো হয়নি।');
      }
      dataSeen = true;
      try {
        data = sjson.parse(part.value, undefined, { protoAction: 'error', constructorAction: 'error' });
      } catch {
        throw new ApiError('INVALID_INPUT', 'ফর্মের তথ্য সঠিক নয়।');
      }
    }
  }
  if (!dataSeen) throw new ApiError('INVALID_INPUT', 'ফর্মের তথ্য পাওয়া যায়নি।');
  return { data, files };
}
