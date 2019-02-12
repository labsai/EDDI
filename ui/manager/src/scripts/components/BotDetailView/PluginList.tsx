import * as React from 'react';
import * as Radium from 'radium';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { CSSProperties } from 'react';
import Plugin from '../PackageDetailView/PluginBoxes/Plugin';
import { IPackage } from '../utils/AxiosFunctions';
import * as _ from 'lodash';
import * as renderIf from 'render-if';

const styles: CSSProperties = {
  pluginList: {
    display: 'grid',
    marginTop: '20px',
    gridGap: '20px',
    gridTemplateColumns: 'repeat(auto-fill, minmax(252px, 1fr))',
    minHeight: '5px',
    minWidth: '5px',
  },
};

interface IProps {
  packagePayload: IPackage;
}

const PluginList: React.StatelessComponent<IProps> = (props: IProps) => {
  return (
    <div>
      {renderIf(
        !_.isEmpty(props.packagePayload.packageData) &&
          !_.isEmpty(props.packagePayload.packageData.packageExtensions),
      )(() => (
        <div style={styles.pluginList}>
          {props.packagePayload.packageData.packageExtensions.map(
            (plug, key) => (
              <Plugin
                key={key}
                pluginType={plug}
                pluginResource={plug.config.uri || ''}
                editDisabled={true}
                packagePayload={props.packagePayload}
              />
            ),
          )}
        </div>
      ))}
    </div>
  );
};

const ComposedPluginList: Component<IProps> = compose<IProps>(
  pure,
  Radium,
  setDisplayName('PluginList'),
)(PluginList);

export default ComposedPluginList;
