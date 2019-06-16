import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { IPlugin } from '../utils/AxiosFunctions';
import { pluginSelector } from '../../selectors/PluginSelectors';
import * as moment from 'moment';
import * as renderIf from 'render-if';
import VersionSelectComponent from '../Assets/VersionSelectComponent';
import TruncateTextComponent from '../Assets/TruncateTextComponent';
import * as _ from 'lodash';
import { connect } from 'react-redux';
import PackagesUsingPlugin from '../PackageDetailView/UsedByComponent/PackagesUsingPlugin';
import styles from './Plugin.styles';
import { ClipLoader } from 'react-spinners';

interface IPublicProps {
  pluginResource: string;
  selectVersion(newVersion): void;
}

interface IPrivateProps extends IPublicProps {
  error: Error;
  plugin: IPlugin;
  isLoading: boolean;
}

const Plugin: React.StatelessComponent<IPrivateProps> = (
  props: IPrivateProps,
) => {
  return (
    <div style={styles.content}>
      {renderIf(!props.error && _.isEmpty(props.plugin))(() => (
        <ClipLoader color={'#0070D2'} />
      ))}
      {renderIf(props.plugin)(() => (
        <div>
          {renderIf(props.error)(() => <p>{'Error: Could not load plugin'}</p>)}
          {renderIf(!props.error && _.isEmpty(props.plugin))(() => (
            <p>{'This plugin does not exist'}</p>
          ))}
          {renderIf(!props.error && !_.isEmpty(props.plugin))(() => (
            <div>
              <div style={styles.topContent}>
                <div style={styles.pluginName}>
                  {props.plugin.name === ''
                    ? props.plugin.id
                    : props.plugin.name}
                </div>
                <div style={styles.versionSelect}>
                  <VersionSelectComponent
                    currentVersion={props.plugin.currentVersion}
                    selectedVersion={props.plugin.version}
                    selectVersion={props.selectVersion}
                  />
                </div>
                <div style={styles.centerFlex} />
                <div style={styles.modifiedDate}>
                  {moment(props.plugin.lastModifiedOn).format('DD.MM.YYYY')}
                </div>
              </div>
              <div style={styles.bottomContent}>
                <TruncateTextComponent
                  text={props.plugin.description}
                  length={80}
                />
                <PackagesUsingPlugin plugin={props.plugin} isSmallName={true} />
              </div>
            </div>
          ))}
        </div>
      ))}
    </div>
  );
};

const ComposedPlugin: Component<IPrivateProps> = compose<
  IPrivateProps,
  IPublicProps
>(pure, connect(pluginSelector), setDisplayName('Plugin'))(Plugin);

export default ComposedPlugin;
