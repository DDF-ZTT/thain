/*
 * Copyright (c) 2019, Xiaomi, Inc.  All rights reserved.
 * This source code is licensed under the Apache License Version 2.0, which
 * can be found in the LICENSE file in the root directory of this source tree.
 */
import { FromDataType } from './index';
import { postForm } from '@/utils/request';

export async function accountLogin(params: FromDataType) {
  return postForm('/api/login', params);
}
