'use strict';
var __awaiter =
  (this && this.__awaiter) ||
  function (thisArg, _arguments, P, generator) {
    return new (P || (P = Promise))(function (resolve, reject) {
      function fulfilled(value) {
        try {
          step(generator.next(value));
        } catch (e) {
          reject(e);
        }
      }
      function rejected(value) {
        try {
          step(generator['throw'](value));
        } catch (e) {
          reject(e);
        }
      }
      function step(result) {
        result.done
          ? resolve(result.value)
          : new P(function (resolve) {
              resolve(result.value);
            }).then(fulfilled, rejected);
      }
      step((generator = generator.apply(thisArg, _arguments || [])).next());
    });
  };
var __generator =
  (this && this.__generator) ||
  function (thisArg, body) {
    var _ = {
        label: 0,
        sent: function () {
          if (t[0] & 1) throw t[1];
          return t[1];
        },
        trys: [],
        ops: [],
      },
      f,
      y,
      t,
      g;
    return (
      (g = { next: verb(0), throw: verb(1), return: verb(2) }),
      typeof Symbol === 'function' &&
        (g[Symbol.iterator] = function () {
          return this;
        }),
      g
    );
    function verb(n) {
      return function (v) {
        return step([n, v]);
      };
    }
    function step(op) {
      if (f) throw new TypeError('Generator is already executing.');
      while (_)
        try {
          if (
            ((f = 1),
            y &&
              (t = y[op[0] & 2 ? 'return' : op[0] ? 'throw' : 'next']) &&
              !(t = t.call(y, op[1])).done)
          )
            return t;
          if (((y = 0), t)) op = [0, t.value];
          switch (op[0]) {
            case 0:
            case 1:
              t = op;
              break;
            case 4:
              _.label++;
              return { value: op[1], done: false };
            case 5:
              _.label++;
              y = op[1];
              op = [0];
              continue;
            case 7:
              op = _.ops.pop();
              _.trys.pop();
              continue;
            default:
              if (
                !((t = _.trys), (t = t.length > 0 && t[t.length - 1])) &&
                (op[0] === 6 || op[0] === 2)
              ) {
                _ = 0;
                continue;
              }
              if (op[0] === 3 && (!t || (op[1] > t[0] && op[1] < t[3]))) {
                _.label = op[1];
                break;
              }
              if (op[0] === 6 && _.label < t[1]) {
                _.label = t[1];
                t = op;
                break;
              }
              if (t && _.label < t[2]) {
                _.label = t[2];
                _.ops.push(op);
                break;
              }
              if (t[2]) _.ops.pop();
              _.trys.pop();
              continue;
          }
          op = body.call(thisArg, _);
        } catch (e) {
          op = [6, e];
          y = 0;
        } finally {
          f = t = 0;
        }
      if (op[0] & 5) throw op[1];
      return { value: op[0] ? op[1] : void 0, done: true };
    }
  };
Object.defineProperty(exports, '__esModule', { value: true });
var axios_1 = require('axios');
var ApiFunctions_1 = require('./ApiFunctions');
var Parser_1 = require('./Parser');
var _ = require('lodash');
var JsonHelpers_1 = require('./helpers/JsonHelpers');
var EddiTypes_1 = require('./EddiTypes');
function setDefaultGlobalHeader(key, value) {
  axios_1.default.defaults.headers.common[key] = value;
}
exports.setDefaultGlobalHeader = setDefaultGlobalHeader;
function getDescriptor(id, version) {
  return __awaiter(this, void 0, void 0, function () {
    var descriptor, _a, _b, e_1;
    return __generator(this, function (_c) {
      switch (_c.label) {
        case 0:
          _c.trys.push([0, 3, , 4]);
          _b = (_a = axios_1.default).get;
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          return [
            4 /*yield*/,
            _b.apply(_a, [
              _c.sent() +
                '/descriptorstore/descriptors/' +
                id +
                '?version=' +
                version,
            ]),
          ];
        case 2:
          descriptor = _c.sent();
          return [
            2 /*return*/,
            {
              createdBy: descriptor.data.createdBy,
              createdOn: descriptor.data.createdOn,
              description: descriptor.data.description,
              lastModifiedBy: descriptor.data.lastModifiedBy,
              lastModifiedOn: descriptor.data.lastModifiedOn,
              name: descriptor.data.name,
              resource: descriptor.data.resource,
            },
          ];
        case 3:
          e_1 = _c.sent();
          console.error('failed to get descriptor. Error: ' + e_1.message);
          throw e_1;
        case 4:
          return [2 /*return*/];
      }
    });
  });
}
exports.getDescriptor = getDescriptor;
exports.ID_LENGTH = 24;
function getBot(botResourceOrId) {
  return __awaiter(this, void 0, void 0, function () {
    return __generator(this, function (_a) {
      switch (_a.label) {
        case 0:
          if (!(_.size(botResourceOrId) > exports.ID_LENGTH))
            return [3 /*break*/, 2];
          return [4 /*yield*/, getSpecificBot(botResourceOrId)];
        case 1:
          return [2 /*return*/, _a.sent()];
        case 2:
          return [4 /*yield*/, getCurrentBot(botResourceOrId)];
        case 3:
          return [2 /*return*/, _a.sent()];
      }
    });
  });
}
exports.getBot = getBot;
function getSpecificBot(botResource) {
  return __awaiter(this, void 0, void 0, function () {
    var id,
      version,
      botDescriptor,
      data,
      currentVersion,
      deploymentStatus,
      err_1;
    return __generator(this, function (_a) {
      switch (_a.label) {
        case 0:
          _a.trys.push([0, 5, , 6]);
          id = Parser_1.default.getId(botResource);
          version = Parser_1.default.getVersion(botResource);
          return [4 /*yield*/, getDescriptor(id, version)];
        case 1:
          botDescriptor = _a.sent();
          return [4 /*yield*/, getBotData(botResource)];
        case 2:
          data = _a.sent();
          return [4 /*yield*/, getCurrentVersion(botResource)];
        case 3:
          currentVersion = _a.sent();
          return [4 /*yield*/, getDeploymentStatus(botResource)];
        case 4:
          deploymentStatus = _a.sent();
          return [
            2 /*return*/,
            {
              id: id,
              version: version,
              currentVersion: currentVersion,
              createdOn: botDescriptor.createdOn,
              description: botDescriptor.description,
              lastModifiedOn: botDescriptor.lastModifiedOn,
              name: botDescriptor.name,
              resource: botDescriptor.resource,
              packages: data.packages,
              channels: data.channels,
              deploymentStatus: deploymentStatus,
            },
          ];
        case 5:
          err_1 = _a.sent();
          console.error('Failed to get bot. Error: ' + err_1.message);
          throw err_1;
        case 6:
          return [2 /*return*/];
      }
    });
  });
}
exports.getSpecificBot = getSpecificBot;
function getBotData(botResource) {
  return __awaiter(this, void 0, void 0, function () {
    var botData, _a, _b, _c, err_2;
    return __generator(this, function (_d) {
      switch (_d.label) {
        case 0:
          _d.trys.push([0, 3, , 4]);
          _b = (_a = axios_1.default).get;
          _c = '';
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          return [
            4 /*yield*/,
            _b.apply(_a, [
              _c +
                _d.sent() +
                Parser_1.default.getApiPathWithIdAndVersion(botResource),
            ]),
          ];
        case 2:
          botData = _d.sent();
          return [2 /*return*/, botData.data];
        case 3:
          err_2 = _d.sent();
          console.error('Failed to get bot data. Error: ' + err_2.message);
          throw err_2;
        case 4:
          return [2 /*return*/];
      }
    });
  });
}
exports.getBotData = getBotData;
function getAllBots() {
  return __awaiter(this, void 0, void 0, function () {
    var botDescriptors, _a, _b, bots, temporaryBotList, i, bot, err_3;
    return __generator(this, function (_c) {
      switch (_c.label) {
        case 0:
          _c.trys.push([0, 7, , 8]);
          _b = (_a = axios_1.default).get;
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          return [
            4 /*yield*/,
            _b.apply(_a, [
              _c.sent() +
                '/botstore/bots/descriptors?limit=' +
                ApiFunctions_1.DEFAULT_LIMIT,
            ]),
          ];
        case 2:
          botDescriptors = _c.sent();
          bots = botDescriptors.data.map(function (bot) {
            var version = Parser_1.default.getVersion(bot.resource);
            return {
              id: Parser_1.default.getId(bot.resource),
              version: version,
              currentVersion: version,
              createdOn: bot.createdOn,
              description: bot.description,
              lastModifiedOn: bot.lastModifiedOn,
              name: bot.name,
              resource: bot.resource,
            };
          });
          temporaryBotList = [];
          i = 0;
          _c.label = 3;
        case 3:
          if (!(i < _.size(bots))) return [3 /*break*/, 6];
          return [4 /*yield*/, getSpecificBot(bots[i].resource)];
        case 4:
          bot = _c.sent();
          temporaryBotList.push(bot);
          _c.label = 5;
        case 5:
          i++;
          return [3 /*break*/, 3];
        case 6:
          // todo: Refactor this function
          return [2 /*return*/, temporaryBotList];
        case 7:
          err_3 = _c.sent();
          console.error('Failed to get all bots. Error: ' + err_3.message);
          throw err_3;
        case 8:
          return [2 /*return*/];
      }
    });
  });
}
exports.getAllBots = getAllBots;
function getBotPackages(resource) {
  return __awaiter(this, void 0, void 0, function () {
    var botData, _a, _b, _c, err_4;
    return __generator(this, function (_d) {
      switch (_d.label) {
        case 0:
          _d.trys.push([0, 3, , 4]);
          _b = (_a = axios_1.default).get;
          _c = '';
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          return [
            4 /*yield*/,
            _b.apply(_a, [
              _c +
                _d.sent() +
                Parser_1.default.getApiPathWithIdAndVersion(resource),
            ]),
          ];
        case 2:
          botData = _d.sent();
          return [2 /*return*/, botData.data.packages];
        case 3:
          err_4 = _d.sent();
          console.error(
            'Failed to get packages in bot. Error: ' + err_4.message,
          );
          throw err_4;
        case 4:
          return [2 /*return*/];
      }
    });
  });
}
exports.getBotPackages = getBotPackages;
function getBotDescriptors(limit, index) {
  return __awaiter(this, void 0, void 0, function () {
    var res, _a, _b, err_5;
    return __generator(this, function (_c) {
      switch (_c.label) {
        case 0:
          _c.trys.push([0, 3, , 4]);
          _b = (_a = axios_1.default).get;
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          return [
            4 /*yield*/,
            _b.apply(_a, [
              _c.sent() +
                '/botstore/bots/descriptors?index=' +
                index +
                '&limit=' +
                limit,
            ]),
          ];
        case 2:
          res = _c.sent();
          return [
            2 /*return*/,
            res.data.map(function (bot) {
              var createdOn = bot.createdOn;
              var description = bot.description;
              var id = Parser_1.default.getId(bot.resource);
              var lastModifiedOn = bot.lastModifiedOn;
              var name = bot.name;
              var resource = bot.resource;
              var version = Parser_1.default.getVersion(bot.resource);
              return {
                createdOn: createdOn,
                description: description,
                id: id,
                lastModifiedOn: lastModifiedOn,
                name: name,
                resource: resource,
                version: version,
                currentVersion: version,
              };
            }),
          ];
        case 3:
          err_5 = _c.sent();
          console.error(err_5);
          throw err_5;
        case 4:
          return [2 /*return*/];
      }
    });
  });
}
exports.getBotDescriptors = getBotDescriptors;
function getCurrentBot(id) {
  return __awaiter(this, void 0, void 0, function () {
    var version, _a, _b, descriptor, data, deploymentStatus, err_6;
    return __generator(this, function (_c) {
      switch (_c.label) {
        case 0:
          _c.trys.push([0, 6, , 7]);
          _b = (_a = axios_1.default).get;
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          return [
            4 /*yield*/,
            _b.apply(_a, [
              _c.sent() + '/botstore/bots/' + id + '/currentversion',
            ]),
          ];
        case 2:
          version = _c.sent().data;
          return [4 /*yield*/, getDescriptor(id, version)];
        case 3:
          descriptor = _c.sent();
          return [4 /*yield*/, getBotData(descriptor.resource)];
        case 4:
          data = _c.sent();
          return [4 /*yield*/, getDeploymentStatus(descriptor.resource)];
        case 5:
          deploymentStatus = _c.sent();
          return [
            2 /*return*/,
            {
              id: id,
              lastModifiedOn: descriptor.lastModifiedOn,
              name: descriptor.name,
              version: version,
              currentVersion: version,
              description: descriptor.description,
              resource: descriptor.resource,
              createdOn: descriptor.createdOn,
              packages: data.packages,
              channels: data.channels,
              deploymentStatus: deploymentStatus,
            },
          ];
        case 6:
          err_6 = _c.sent();
          console.error('Failed to get current bot. Error: ' + err_6.message);
          throw err_6;
        case 7:
          return [2 /*return*/];
      }
    });
  });
}
exports.getCurrentBot = getCurrentBot;
function getPluginData(pluginResource) {
  return __awaiter(this, void 0, void 0, function () {
    var res, _a, _b, _c, _d, err_7;
    return __generator(this, function (_e) {
      switch (_e.label) {
        case 0:
          _e.trys.push([0, 3, , 4]);
          _b = (_a = axios_1.default).get;
          _c = '';
          _d = '';
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          return [
            4 /*yield*/,
            _b.apply(_a, [
              _c +
                (_d +
                  _e.sent() +
                  Parser_1.default.getApiPathWithIdAndVersion(pluginResource)),
            ]),
          ];
        case 2:
          res = _e.sent();
          return [2 /*return*/, res.data];
        case 3:
          err_7 = _e.sent();
          console.error('Failed to get PluginData. Error: ' + err_7.message);
          throw err_7;
        case 4:
          return [2 /*return*/];
      }
    });
  });
}
exports.getPluginData = getPluginData;
function getPackageData(packageResource) {
  return __awaiter(this, void 0, void 0, function () {
    var res, _a, _b, _c, err_8;
    return __generator(this, function (_d) {
      switch (_d.label) {
        case 0:
          _d.trys.push([0, 3, , 4]);
          _b = (_a = axios_1.default).get;
          _c = '';
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          return [
            4 /*yield*/,
            _b.apply(_a, [
              _c +
                _d.sent() +
                Parser_1.default.getApiPathWithIdAndVersion(packageResource),
            ]),
          ];
        case 2:
          res = _d.sent();
          return [2 /*return*/, res.data];
        case 3:
          err_8 = _d.sent();
          console.error('Failed to get PackageData. Error: ' + err_8.message);
          throw err_8;
        case 4:
          return [2 /*return*/];
      }
    });
  });
}
exports.getPackageData = getPackageData;
function getPlugin(pluginResource) {
  return __awaiter(this, void 0, void 0, function () {
    var res, _a, _b, pluginData, currentVersion, err_9;
    return __generator(this, function (_c) {
      switch (_c.label) {
        case 0:
          _c.trys.push([0, 5, , 6]);
          _b = (_a = axios_1.default).get;
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          return [
            4 /*yield*/,
            _b.apply(_a, [
              _c.sent() +
                '/descriptorstore/descriptors/' +
                Parser_1.default.getIdAndVersion(pluginResource),
            ]),
          ];
        case 2:
          res = _c.sent();
          return [4 /*yield*/, getPluginData(pluginResource)];
        case 3:
          pluginData = _c.sent();
          return [4 /*yield*/, getCurrentVersion(pluginResource)];
        case 4:
          currentVersion = _c.sent();
          return [
            2 /*return*/,
            {
              id: Parser_1.default.getId(res.data.resource),
              version: Parser_1.default.getVersion(res.data.resource),
              currentVersion: currentVersion,
              createdOn: res.data.createdOn,
              description: res.data.description,
              lastModifiedOn: res.data.lastModifiedOn,
              name: res.data.name,
              resource: res.data.resource,
              pluginData: pluginData,
            },
          ];
        case 5:
          err_9 = _c.sent();
          console.error('Failed to get plugin. Error: ' + err_9.message);
          throw err_9;
        case 6:
          return [2 /*return*/];
      }
    });
  });
}
exports.getPlugin = getPlugin;
function getPackageDescriptors(limit, index) {
  if (limit === void 0) {
    limit = ApiFunctions_1.DEFAULT_LIMIT;
  }
  if (index === void 0) {
    index = 0;
  }
  return __awaiter(this, void 0, void 0, function () {
    var res, _a, _b, err_10;
    return __generator(this, function (_c) {
      switch (_c.label) {
        case 0:
          _c.trys.push([0, 3, , 4]);
          _b = (_a = axios_1.default).get;
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          return [
            4 /*yield*/,
            _b.apply(_a, [
              _c.sent() +
                '/packagestore/packages/descriptors?limit=' +
                limit +
                '&index=' +
                index,
            ]),
          ];
        case 2:
          res = _c.sent();
          return [
            2 /*return*/,
            Parser_1.default.getDetailedDescriptors(res, true),
          ];
        case 3:
          err_10 = _c.sent();
          console.error(
            'Failed to get package descriptors. Error: ' + err_10.message,
          );
          throw err_10;
        case 4:
          return [2 /*return*/];
      }
    });
  });
}
exports.getPackageDescriptors = getPackageDescriptors;
function getPackage(resource) {
  return __awaiter(this, void 0, void 0, function () {
    var id, version, descriptor, packageData, currentVersion, err_11;
    return __generator(this, function (_a) {
      switch (_a.label) {
        case 0:
          _a.trys.push([0, 4, , 5]);
          id = Parser_1.default.getId(resource);
          version = Parser_1.default.getVersion(resource);
          return [4 /*yield*/, getDescriptor(id, version)];
        case 1:
          descriptor = _a.sent();
          return [4 /*yield*/, getPackageData(resource)];
        case 2:
          packageData = _a.sent();
          return [4 /*yield*/, getCurrentVersion(resource)];
        case 3:
          currentVersion = _a.sent();
          return [
            2 /*return*/,
            {
              id: id,
              version: version,
              currentVersion: currentVersion,
              lastModifiedOn: descriptor.lastModifiedOn,
              name: descriptor.name,
              description: descriptor.description,
              resource: descriptor.resource,
              createdOn: descriptor.createdOn,
              packageData: packageData,
            },
          ];
        case 4:
          err_11 = _a.sent();
          console.error('Failed to get package. Error: ' + err_11.message);
          throw err_11;
        case 5:
          return [2 /*return*/];
      }
    });
  });
}
exports.getPackage = getPackage;
function getCurrentPackage(id) {
  return __awaiter(this, void 0, void 0, function () {
    var version, _a, _b, descriptor, err_12;
    return __generator(this, function (_c) {
      switch (_c.label) {
        case 0:
          _c.trys.push([0, 4, , 5]);
          _b = (_a = axios_1.default).get;
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          return [
            4 /*yield*/,
            _b.apply(_a, [
              _c.sent() + '/packagestore/packages/' + id + '/currentversion',
            ]),
          ];
        case 2:
          version = _c.sent().data;
          return [4 /*yield*/, getDescriptor(id, version)];
        case 3:
          descriptor = _c.sent();
          return [
            2 /*return*/,
            {
              id: id,
              version: version,
              lastModifiedOn: descriptor.lastModifiedOn,
              currentVersion: version,
              name: descriptor.name,
              description: descriptor.description,
              resource: descriptor.resource,
              createdOn: descriptor.createdOn,
            },
          ];
        case 4:
          err_12 = _c.sent();
          console.error(
            'Failed to get current package. Error: ' + err_12.message,
          );
          throw err_12;
        case 5:
          return [2 /*return*/];
      }
    });
  });
}
exports.getCurrentPackage = getCurrentPackage;
function getCurrentPlugin(resource) {
  return __awaiter(this, void 0, void 0, function () {
    var requestUri, _a, version, id, descriptor, err_13;
    return __generator(this, function (_b) {
      switch (_b.label) {
        case 0:
          _b.trys.push([0, 5, , 6]);
          _a = '';
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          requestUri =
            _a +
            _b.sent() +
            Parser_1.default.getApiPathWithIdAndVersion(resource);
          requestUri =
            requestUri.substring(0, requestUri.indexOf('?version')) +
            '/currentversion';
          return [4 /*yield*/, axios_1.default.get(requestUri)];
        case 2:
          return [4 /*yield*/, _b.sent().data];
        case 3:
          version = _b.sent();
          id = Parser_1.default.getId(resource);
          return [4 /*yield*/, getDescriptor(id, version)];
        case 4:
          descriptor = _b.sent();
          return [
            2 /*return*/,
            {
              id: id,
              version: version,
              lastModifiedOn: descriptor.lastModifiedOn,
              currentVersion: version,
              name: descriptor.name,
              description: descriptor.description,
              resource: descriptor.resource,
              createdOn: descriptor.createdOn,
            },
          ];
        case 5:
          err_13 = _b.sent();
          console.error(
            'Failed to get current plugin. Error: ' + err_13.message,
          );
          throw err_13;
        case 6:
          return [2 /*return*/];
      }
    });
  });
}
exports.getCurrentPlugin = getCurrentPlugin;
function getCurrentVersion(resource) {
  return __awaiter(this, void 0, void 0, function () {
    var uri, _a, requestUri, currentVersion, err_14;
    return __generator(this, function (_b) {
      switch (_b.label) {
        case 0:
          _b.trys.push([0, 3, , 4]);
          _a = '';
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          uri =
            _a +
            _b.sent() +
            Parser_1.default.getApiPathWithIdAndVersion(resource);
          requestUri =
            uri.substring(0, uri.indexOf('?version=')) + '/currentversion';
          return [4 /*yield*/, axios_1.default.get(requestUri)];
        case 2:
          currentVersion = _b.sent();
          return [2 /*return*/, parseInt(currentVersion.data, 10)];
        case 3:
          err_14 = _b.sent();
          console.error(
            'Failed to get current version. Error: ' + err_14.message,
          );
          throw err_14;
        case 4:
          return [2 /*return*/];
      }
    });
  });
}
exports.getCurrentVersion = getCurrentVersion;
function updateBot(currentBot, updatablePackageResource) {
  return __awaiter(this, void 0, void 0, function () {
    var currentBotUri, newPackage_1, packages, newBot, err_15;
    return __generator(this, function (_a) {
      switch (_a.label) {
        case 0:
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          currentBotUri =
            _a.sent() +
            '/botstore/bots/' +
            currentBot.id +
            '?version=' +
            currentBot.version;
          _a.label = 2;
        case 2:
          _a.trys.push([2, 6, , 7]);
          return [
            4 /*yield*/,
            getCurrentPackage(Parser_1.default.getId(updatablePackageResource)),
          ];
        case 3:
          newPackage_1 = _a.sent();
          packages = currentBot.packages.map(function (pkg) {
            if (Parser_1.default.getId(pkg) === newPackage_1.id) {
              return newPackage_1.resource;
            }
            return pkg;
          });
          return [
            4 /*yield*/,
            axios_1.default.put(currentBotUri, {
              packages: packages,
              channels: currentBot.channels,
            }),
          ];
        case 4:
          _a.sent();
          return [4 /*yield*/, getCurrentBot(currentBot.id)];
        case 5:
          newBot = _a.sent();
          return [2 /*return*/, newBot];
        case 6:
          err_15 = _a.sent();
          console.error('Failed to update bot. Error: ' + err_15.message);
          throw err_15;
        case 7:
          return [2 /*return*/];
      }
    });
  });
}
exports.updateBot = updateBot;
function updateBotPackages(currentBot, packages) {
  return __awaiter(this, void 0, void 0, function () {
    var currentBotUri, newBot, err_16;
    return __generator(this, function (_a) {
      switch (_a.label) {
        case 0:
          _a.trys.push([0, 4, , 5]);
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          currentBotUri =
            _a.sent() +
            '/botstore/bots/' +
            currentBot.id +
            '?version=' +
            currentBot.version;
          return [
            4 /*yield*/,
            axios_1.default.put(currentBotUri, {
              packages: packages,
              channels: currentBot.channels,
            }),
          ];
        case 2:
          _a.sent();
          return [4 /*yield*/, getCurrentBot(currentBot.id)];
        case 3:
          newBot = _a.sent();
          return [2 /*return*/, newBot];
        case 4:
          err_16 = _a.sent();
          console.error(
            'Failed to update bot packages. Error: ' + err_16.message,
          );
          throw err_16;
        case 5:
          return [2 /*return*/];
      }
    });
  });
}
exports.updateBotPackages = updateBotPackages;
function addPackageToBot(currentBot, packageResource) {
  return __awaiter(this, void 0, void 0, function () {
    var currentBotUri, botData, updatedBot, err_17;
    return __generator(this, function (_a) {
      switch (_a.label) {
        case 0:
          _a.trys.push([0, 7, , 8]);
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          currentBotUri =
            _a.sent() +
            '/botstore/bots/' +
            currentBot.id +
            '?version=' +
            currentBot.version;
          botData = void 0;
          if (!_.isUndefined(currentBot.packages)) return [3 /*break*/, 3];
          return [4 /*yield*/, getBotData(currentBot.resource)];
        case 2:
          botData = _a.sent();
          return [3 /*break*/, 4];
        case 3:
          botData = {
            packages: currentBot.packages,
            channels: currentBot.channels,
          };
          _a.label = 4;
        case 4:
          botData.packages.push(packageResource);
          return [4 /*yield*/, axios_1.default.put(currentBotUri, botData)];
        case 5:
          _a.sent();
          return [4 /*yield*/, getCurrentBot(currentBot.id)];
        case 6:
          updatedBot = _a.sent();
          return [2 /*return*/, updatedBot];
        case 7:
          err_17 = _a.sent();
          console.error(
            'Failed to add package to bot. Error: ' + err_17.message,
          );
          throw err_17;
        case 8:
          return [2 /*return*/];
      }
    });
  });
}
exports.addPackageToBot = addPackageToBot;
function updateResourcesInBot(botResource, packageResources) {
  return __awaiter(this, void 0, void 0, function () {
    var currentBotUri, oldBot, newBotPackageList, newBot, err_18;
    return __generator(this, function (_a) {
      switch (_a.label) {
        case 0:
          _a.trys.push([0, 5, , 6]);
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          currentBotUri =
            _a.sent() +
            '/botstore/bots/' +
            Parser_1.default.getIdAndVersion(botResource);
          return [
            4 /*yield*/,
            getCurrentBot(Parser_1.default.getId(botResource)),
          ];
        case 2:
          oldBot = _a.sent();
          newBotPackageList = oldBot.packages.map(function (pkg) {
            return (
              packageResources.find(function (resource) {
                return (
                  Parser_1.default.getId(resource) ===
                  Parser_1.default.getId(pkg)
                );
              }) || pkg
            );
          });
          return [
            4 /*yield*/,
            axios_1.default.put(currentBotUri, {
              packages: newBotPackageList,
              channels: oldBot.channels,
            }),
          ];
        case 3:
          _a.sent();
          return [
            4 /*yield*/,
            getCurrentBot(Parser_1.default.getId(botResource)),
          ];
        case 4:
          newBot = _a.sent();
          return [2 /*return*/, newBot];
        case 5:
          err_18 = _a.sent();
          console.error(
            'Failed to update resources in bot. Error: ' + err_18.message,
          );
          throw err_18;
        case 6:
          return [2 /*return*/];
      }
    });
  });
}
exports.updateResourcesInBot = updateResourcesInBot;
function updateBots(bots) {
  return __awaiter(this, void 0, void 0, function () {
    var newBots, i, newBot, err_19;
    return __generator(this, function (_a) {
      switch (_a.label) {
        case 0:
          _a.trys.push([0, 5, , 6]);
          newBots = [];
          i = 0;
          _a.label = 1;
        case 1:
          if (!(i < _.size(bots))) return [3 /*break*/, 4];
          return [
            4 /*yield*/,
            updateResourcesInBot(bots[i].botResource, bots[i].packageResources),
          ];
        case 2:
          newBot = _a.sent();
          newBots.push(newBot);
          _a.label = 3;
        case 3:
          i++;
          return [3 /*break*/, 1];
        case 4:
          return [2 /*return*/, newBots];
        case 5:
          err_19 = _a.sent();
          console.error('Failed to update bots. Error: ' + err_19.message);
          throw err_19;
        case 6:
          return [2 /*return*/];
      }
    });
  });
}
exports.updateBots = updateBots;
function updatePackageExtension(externalPackages, newExtensionResource) {
  var newExtensionId = Parser_1.default.getId(newExtensionResource);
  var updatedExternalPackages = externalPackages.map(function (
    externalPackage,
  ) {
    if (
      externalPackage.config &&
      externalPackage.config.uri &&
      Parser_1.default.getId(externalPackage.config.uri) === newExtensionId
    ) {
      externalPackage.config.uri = newExtensionResource;
    }
    if (!_.isEmpty(externalPackage.extensions)) {
      for (
        var _i = 0, _a = Object.values(externalPackage.extensions);
        _i < _a.length;
        _i++
      ) {
        var extensions = _a[_i];
        if (!_.isEmpty(extensions)) {
          for (
            var _b = 0, extensions_1 = extensions;
            _b < extensions_1.length;
            _b++
          ) {
            var extension = extensions_1[_b];
            if (
              extension.config &&
              extension.config.uri &&
              Parser_1.default.getId(extension.config.uri) === newExtensionId
            ) {
              extension.config.uri = newExtensionResource;
            }
          }
        }
      }
    }
    return externalPackage;
  });
  return updatedExternalPackages;
}
exports.updatePackageExtension = updatePackageExtension;
function updatePackage(currentPackage, updatablePluginResource) {
  return __awaiter(this, void 0, void 0, function () {
    var currentPackageUri,
      packageData,
      newPlugin,
      newPackageData,
      newPackage,
      err_20;
    return __generator(this, function (_a) {
      switch (_a.label) {
        case 0:
          _a.trys.push([0, 7, , 8]);
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          currentPackageUri =
            _a.sent() +
            '/packagestore/packages/' +
            currentPackage.id +
            '?version=' +
            currentPackage.version;
          return [4 /*yield*/, axios_1.default.get(currentPackageUri)];
        case 2:
          packageData = _a.sent().data;
          return [4 /*yield*/, getCurrentPlugin(updatablePluginResource)];
        case 3:
          newPlugin = _a.sent();
          return [
            4 /*yield*/,
            updatePackageExtension(
              packageData.packageExtensions,
              newPlugin.resource,
            ),
          ];
        case 4:
          newPackageData = _a.sent();
          return [
            4 /*yield*/,
            axios_1.default.put(currentPackageUri, {
              packageExtensions: newPackageData,
            }),
          ];
        case 5:
          _a.sent();
          return [4 /*yield*/, getCurrentPackage(currentPackage.id)];
        case 6:
          newPackage = _a.sent();
          return [2 /*return*/, newPackage];
        case 7:
          err_20 = _a.sent();
          console.error('Failed to update package. Error: ' + err_20.message);
          throw err_20;
        case 8:
          return [2 /*return*/];
      }
    });
  });
}
exports.updatePackage = updatePackage;
function patchDescriptor(resource, name, description) {
  return __awaiter(this, void 0, void 0, function () {
    var res, _a, _b, err_21;
    return __generator(this, function (_c) {
      switch (_c.label) {
        case 0:
          _c.trys.push([0, 3, , 4]);
          _b = (_a = axios_1.default).patch;
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          return [
            4 /*yield*/,
            _b.apply(_a, [
              _c.sent() +
                '/descriptorstore/descriptors/' +
                Parser_1.default.getIdAndVersion(resource),
              {
                operation: 'SET',
                document: {
                  name: name,
                  description: description,
                },
              },
            ]),
          ];
        case 2:
          res = _c.sent();
          return [2 /*return*/, res.config.url];
        case 3:
          err_21 = _c.sent();
          console.error('Failed to patch bot. Error: ' + err_21.message);
          throw err_21;
        case 4:
          return [2 /*return*/];
      }
    });
  });
}
exports.patchDescriptor = patchDescriptor;
function createNewBot(name, description) {
  return __awaiter(this, void 0, void 0, function () {
    var response, resource, err_22;
    return __generator(this, function (_a) {
      switch (_a.label) {
        case 0:
          _a.trys.push([0, 3, , 4]);
          return [
            4 /*yield*/,
            JsonHelpers_1.postJsonHelper('/botstore/bots', {}),
          ];
        case 1:
          response = _a.sent();
          resource = response.headers.location;
          return [4 /*yield*/, patchDescriptor(resource, name, description)];
        case 2:
          _a.sent();
          return [
            2 /*return*/,
            Parser_1.default.getId(response.headers.location),
          ];
        case 3:
          err_22 = _a.sent();
          console.error('Failed to create bot. Error: ' + err_22.message);
          throw err_22;
        case 4:
          return [2 /*return*/];
      }
    });
  });
}
exports.createNewBot = createNewBot;
function createNewPackage(name, description, extensions) {
  return __awaiter(this, void 0, void 0, function () {
    var response, resource, err_23;
    return __generator(this, function (_a) {
      switch (_a.label) {
        case 0:
          _a.trys.push([0, 2, , 3]);
          return [
            4 /*yield*/,
            JsonHelpers_1.postJsonHelper('/packagestore/packages/', {
              packageExtensions: extensions,
            }),
          ];
        case 1:
          response = _a.sent();
          resource = response.headers.location;
          patchDescriptor(resource, name, description);
          return [2 /*return*/, Parser_1.default.getId(resource)];
        case 2:
          err_23 = _a.sent();
          console.error('Failed to create package. Error: ' + err_23.message);
          throw err_23;
        case 3:
          return [2 /*return*/];
      }
    });
  });
}
exports.createNewPackage = createNewPackage;
function addPluginType(resource, extensions) {
  return __awaiter(this, void 0, void 0, function () {
    var newPackage, err_24;
    return __generator(this, function (_a) {
      switch (_a.label) {
        case 0:
          _a.trys.push([0, 3, , 4]);
          return [
            4 /*yield*/,
            JsonHelpers_1.putHelper(
              resource,
              Parser_1.default.getApiPath(resource),
              {
                packageExtensions: extensions,
              },
            ),
          ];
        case 1:
          _a.sent();
          return [
            4 /*yield*/,
            getCurrentPackage(Parser_1.default.getId(resource)),
          ];
        case 2:
          newPackage = _a.sent();
          return [2 /*return*/, newPackage];
        case 3:
          err_24 = _a.sent();
          console.error('Failed to save plugins. Error: ' + err_24.message);
          throw err_24;
        case 4:
          return [2 /*return*/];
      }
    });
  });
}
exports.addPluginType = addPluginType;
function getAllDefaultPluginTypes() {
  return __awaiter(this, void 0, void 0, function () {
    var res, _a, _b, err_25;
    return __generator(this, function (_c) {
      switch (_c.label) {
        case 0:
          _c.trys.push([0, 3, , 4]);
          _b = (_a = axios_1.default).get;
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          return [
            4 /*yield*/,
            _b.apply(_a, [_c.sent() + '/extensionstore/extensions']),
          ];
        case 2:
          res = _c.sent();
          return [2 /*return*/, res.data];
        case 3:
          err_25 = _c.sent();
          console.error('Failed to get extensions. Error: ' + err_25.message);
          throw err_25;
        case 4:
          return [2 /*return*/];
      }
    });
  });
}
exports.getAllDefaultPluginTypes = getAllDefaultPluginTypes;
function getPluginDescriptors(pluginType, limit, index) {
  return __awaiter(this, void 0, void 0, function () {
    var res, _a, _b, _c, _d, _e, _f, _g, _h, _j, _k, _l, _m, _o, err_26;
    return __generator(this, function (_p) {
      switch (_p.label) {
        case 0:
          _p.trys.push([0, 15, , 16]);
          res = void 0;
          _a = pluginType;
          switch (_a) {
            case EddiTypes_1.BEHAVIOR:
              return [3 /*break*/, 1];
            case EddiTypes_1.OUTPUT:
              return [3 /*break*/, 4];
            case EddiTypes_1.REGULAR_DICTIONARY:
              return [3 /*break*/, 7];
            case EddiTypes_1.HTTPCALLS:
              return [3 /*break*/, 10];
          }
          return [3 /*break*/, 13];
        case 1:
          _c = (_b = axios_1.default).get;
          _d = '';
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 2:
          return [
            4 /*yield*/,
            _c.apply(_b, [
              _d +
                _p.sent() +
                EddiTypes_1.BEHAVIOR_PATH +
                '/descriptors?index=' +
                index +
                '&limit=' +
                limit,
            ]),
          ];
        case 3:
          res = _p.sent();
          return [3 /*break*/, 14];
        case 4:
          _f = (_e = axios_1.default).get;
          _g = '';
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 5:
          return [
            4 /*yield*/,
            _f.apply(_e, [
              _g +
                _p.sent() +
                EddiTypes_1.OUTPUT_PATH +
                '/descriptors?index=' +
                index +
                '&limit=' +
                limit,
            ]),
          ];
        case 6:
          res = _p.sent();
          return [3 /*break*/, 14];
        case 7:
          _j = (_h = axios_1.default).get;
          _k = '';
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 8:
          return [
            4 /*yield*/,
            _j.apply(_h, [
              _k +
                _p.sent() +
                EddiTypes_1.REGULAR_DICTIONARY_PATH +
                '/descriptors?index=' +
                index +
                '&limit=' +
                limit,
            ]),
          ];
        case 9:
          res = _p.sent();
          return [3 /*break*/, 14];
        case 10:
          _m = (_l = axios_1.default).get;
          _o = '';
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 11:
          return [
            4 /*yield*/,
            _m.apply(_l, [
              _o +
                _p.sent() +
                EddiTypes_1.HTTPCALLS_PATH +
                '/descriptors?index=' +
                index +
                '&limit=' +
                limit,
            ]),
          ];
        case 12:
          res = _p.sent();
          return [3 /*break*/, 14];
        case 13:
          res = null;
          _p.label = 14;
        case 14:
          if (res !== null) {
            return [
              2 /*return*/,
              res.data.map(function (pkg) {
                var version = Parser_1.default.getVersion(pkg.resource);
                return {
                  createdOn: pkg.createdOn,
                  description: pkg.description,
                  id: Parser_1.default.getId(pkg.resource),
                  lastModifiedOn: pkg.lastModifiedOn,
                  name: pkg.name,
                  resource: pkg.resource,
                  version: version,
                  currentVersion: version,
                };
              }),
            ];
          } else {
            return [2 /*return*/, null];
          }
          return [3 /*break*/, 16];
        case 15:
          err_26 = _p.sent();
          console.error(
            'Failed to get plugin descriptors. Error: ' + err_26.message,
          );
          throw err_26;
        case 16:
          return [2 /*return*/];
      }
    });
  });
}
exports.getPluginDescriptors = getPluginDescriptors;
function getBotsUsingPackage(packageResource, usingOldVersions) {
  if (usingOldVersions === void 0) {
    usingOldVersions = false;
  }
  return __awaiter(this, void 0, void 0, function () {
    var config, res, _a, _b, err_27;
    return __generator(this, function (_c) {
      switch (_c.label) {
        case 0:
          _c.trys.push([0, 3, , 4]);
          config = {
            headers: {
              Accept: 'application/json',
              'Content-Type': 'text/plain',
            },
          };
          _b = (_a = axios_1.default).post;
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          return [
            4 /*yield*/,
            _b.apply(_a, [
              _c.sent() +
                '/botstore/bots/descriptors?limit=500&includePreviousVersions=' +
                usingOldVersions,
              packageResource,
              config,
            ]),
          ];
        case 2:
          res = _c.sent();
          return [
            2 /*return*/,
            Parser_1.default.getDetailedDescriptors(res, true),
          ];
        case 3:
          err_27 = _c.sent();
          console.error(
            'Failed to get bots using this package. Error: ' + err_27.message,
          );
          throw err_27;
        case 4:
          return [2 /*return*/];
      }
    });
  });
}
exports.getBotsUsingPackage = getBotsUsingPackage;
function getPackagesUsingPlugin(pluginResource, usingOldVersions) {
  if (usingOldVersions === void 0) {
    usingOldVersions = false;
  }
  return __awaiter(this, void 0, void 0, function () {
    var config, res, _a, _b, err_28;
    return __generator(this, function (_c) {
      switch (_c.label) {
        case 0:
          _c.trys.push([0, 3, , 4]);
          config = {
            headers: {
              Accept: 'application/json',
              'Content-Type': 'text/plain',
            },
          };
          _b = (_a = axios_1.default).post;
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          return [
            4 /*yield*/,
            _b.apply(_a, [
              _c.sent() +
                '/packagestore/packages/descriptors?limit=500&includePreviousVersions=' +
                usingOldVersions,
              pluginResource,
              config,
            ]),
          ];
        case 2:
          res = _c.sent();
          return [
            2 /*return*/,
            Parser_1.default.getDetailedDescriptors(res, true),
          ];
        case 3:
          err_28 = _c.sent();
          console.error(
            'Failed to get packages using this plugin. Error: ' +
              err_28.message,
          );
          throw err_28;
        case 4:
          return [2 /*return*/];
      }
    });
  });
}
exports.getPackagesUsingPlugin = getPackagesUsingPlugin;
function updateJsonData(resource, data) {
  return __awaiter(this, void 0, void 0, function () {
    var _a, _b, _c, err_29;
    return __generator(this, function (_d) {
      switch (_d.label) {
        case 0:
          _d.trys.push([0, 3, , 4]);
          _b = (_a = axios_1.default).put;
          _c = '';
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          return [
            4 /*yield*/,
            _b.apply(_a, [
              _c +
                _d.sent() +
                Parser_1.default.getApiPathWithIdAndVersion(resource),
              data,
            ]),
          ];
        case 2:
          _d.sent();
          return [3 /*break*/, 4];
        case 3:
          err_29 = _d.sent();
          console.error('Failed to update JSON data. Error: ' + err_29.message);
          throw err_29;
        case 4:
          return [2 /*return*/];
      }
    });
  });
}
exports.updateJsonData = updateJsonData;
function postNewConfig(type, name, description, data) {
  return __awaiter(this, void 0, void 0, function () {
    var configPath, response, resource, err_30;
    return __generator(this, function (_a) {
      switch (_a.label) {
        case 0:
          switch (type) {
            case EddiTypes_1.REGULAR_DICTIONARY:
              configPath = EddiTypes_1.REGULAR_DICTIONARY_PATH;
              break;
            case EddiTypes_1.BEHAVIOR:
              configPath = EddiTypes_1.BEHAVIOR_PATH;
              break;
            case EddiTypes_1.OUTPUT:
              configPath = EddiTypes_1.OUTPUT_PATH;
              break;
            case EddiTypes_1.BOT:
              configPath = EddiTypes_1.BOT_PATH;
              break;
            case EddiTypes_1.PACKAGE:
              configPath = EddiTypes_1.PACKAGE_PATH;
              break;
            case EddiTypes_1.HTTPCALLS:
              configPath = EddiTypes_1.HTTPCALLS_PATH;
              break;
            default:
              console.error('Could not create new config of type: ' + type);
          }
          _a.label = 1;
        case 1:
          _a.trys.push([1, 4, , 5]);
          return [
            4 /*yield*/,
            JsonHelpers_1.postJsonHelper(configPath, JSON.parse(data)),
          ];
        case 2:
          response = _a.sent();
          resource = response.headers.location;
          return [4 /*yield*/, patchDescriptor(resource, name, description)];
        case 3:
          _a.sent();
          return [2 /*return*/, resource];
        case 4:
          err_30 = _a.sent();
          console.error(
            'Failed to create new config. Error: ' + err_30.message,
          );
          throw err_30;
        case 5:
          return [2 /*return*/];
      }
    });
  });
}
exports.postNewConfig = postNewConfig;
function updatePackages(pluginResource, packages) {
  return __awaiter(this, void 0, void 0, function () {
    var updatedPackages, i, currentPackage, updatedPackage, err_31;
    return __generator(this, function (_a) {
      switch (_a.label) {
        case 0:
          _a.trys.push([0, 6, , 7]);
          updatedPackages = [];
          i = 0;
          _a.label = 1;
        case 1:
          if (!(i < _.size(packages))) return [3 /*break*/, 5];
          return [
            4 /*yield*/,
            getCurrentPackage(Parser_1.default.getId(packages[i])),
          ];
        case 2:
          currentPackage = _a.sent();
          return [4 /*yield*/, updatePackage(currentPackage, pluginResource)];
        case 3:
          updatedPackage = _a.sent();
          updatedPackages.push(updatedPackage);
          _a.label = 4;
        case 4:
          i++;
          return [3 /*break*/, 1];
        case 5:
          return [2 /*return*/, updatedPackages];
        case 6:
          err_31 = _a.sent();
          console.error('Failed to update packages. Error: ' + err_31.message);
          throw err_31;
        case 7:
          return [2 /*return*/];
      }
    });
  });
}
exports.updatePackages = updatePackages;
function getDeploymentStatus(resource) {
  return __awaiter(this, void 0, void 0, function () {
    var res, _a, _b, err_32;
    return __generator(this, function (_c) {
      switch (_c.label) {
        case 0:
          _c.trys.push([0, 3, , 4]);
          _b = (_a = axios_1.default).get;
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          return [
            4 /*yield*/,
            _b.apply(_a, [
              _c.sent() +
                '/administration/unrestricted/deploymentstatus/' +
                Parser_1.default.getIdAndVersion(resource),
            ]),
          ];
        case 2:
          res = _c.sent();
          return [2 /*return*/, res.data];
        case 3:
          err_32 = _c.sent();
          console.error(
            'Failed to get deployment status. Error: ' + err_32.message,
          );
          throw err_32;
        case 4:
          return [2 /*return*/];
      }
    });
  });
}
exports.getDeploymentStatus = getDeploymentStatus;
function deployBot(resource) {
  return __awaiter(this, void 0, void 0, function () {
    var _a, _b, err_33;
    return __generator(this, function (_c) {
      switch (_c.label) {
        case 0:
          _c.trys.push([0, 3, , 4]);
          _b = (_a = axios_1.default).post;
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          return [
            4 /*yield*/,
            _b.apply(_a, [
              _c.sent() +
                '/administration/unrestricted/deploy/' +
                Parser_1.default.getIdAndVersion(resource),
            ]),
          ];
        case 2:
          _c.sent();
          return [3 /*break*/, 4];
        case 3:
          err_33 = _c.sent();
          console.error('Failed to deploy bot. Error: ' + err_33.message);
          throw err_33;
        case 4:
          return [2 /*return*/];
      }
    });
  });
}
exports.deployBot = deployBot;
function undeployBot(resource) {
  return __awaiter(this, void 0, void 0, function () {
    var res, _a, _b;
    return __generator(this, function (_c) {
      switch (_c.label) {
        case 0:
          _b = (_a = axios_1.default).post;
          return [4 /*yield*/, ApiFunctions_1.getAPIUrl()];
        case 1:
          return [
            4 /*yield*/,
            _b.apply(_a, [
              _c.sent() +
                '/administration/unrestricted/undeploy/' +
                Parser_1.default.getIdAndVersion(resource),
            ]),
          ];
        case 2:
          res = _c.sent();
          return [2 /*return*/, res];
      }
    });
  });
}
exports.undeployBot = undeployBot;
