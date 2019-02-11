import * as React from 'react';
import * as Radium from 'radium';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { CSSProperties } from 'react';
import Plugin from './Plugin';
import { IPackage, IPluginTypes } from '../utils/AxiosFunctions';
import { parsePlugin } from '../utils/helpers/PluginParser';

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
  pluginTypes: IPluginTypes[];
  packagePayload: IPackage;
}

const PluginList: React.StatelessComponent<IProps> = (props: IProps) => {
  return (
    <div style={styles.pluginList}>
      {props.pluginTypes.map((plug, key) => (
        <Plugin
          key={key}
          pluginType={plug}
          pluginResource={plug.config.uri || ''}
          packagePayload={props.packagePayload}
        />
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
