import { assert } from 'chai';

describe('Smoke test', function () {
  it('should support async functions', async function () {
    let data: string = await Promise.resolve('test');
    assert.equal(data, 'test');
  });
});
