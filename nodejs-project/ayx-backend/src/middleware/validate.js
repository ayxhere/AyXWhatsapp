// Request validation helpers (zod). Every mutating endpoint validates its
// input; failures return 422 with a machine-readable error code.

import { z } from 'zod';

export function validate(schema, source = 'body') {
  return (req, res, next) => {
    const result = schema.safeParse(req[source]);
    if (!result.success) {
      return res.status(422).json({
        error: {
          code: 'validation_error',
          message: 'Request validation failed.',
          details: result.error.flatten(),
        },
      });
    }
    req[source] = result.data;
    next();
  };
}

export const paginationSchema = z.object({
  page: z.coerce.number().int().min(1).default(1),
  per_page: z.coerce.number().int().min(1).max(100).default(20),
});

export function paginated(data, total, page, perPage) {
  return { data, meta: { page, per_page: perPage, total } };
}

// Common field schemas
export const cuid = z.string().min(1).max(64);
export const isoDate = z
  .string()
  .datetime({ offset: true })
  .or(z.string().datetime())
  .transform((s) => new Date(s));
