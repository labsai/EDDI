import * as React from 'react';
import Radium from 'radium';
import { compose, pure, setDisplayName } from 'recompose';
import Plugin from '../PackageDetailView/PluginBoxes/Plugin';
import { IPackage } from '../utils/AxiosFunctions';
import * as _ from 'lodash';
import * as renderIf from 'render-if';

const styles: { [key: string]: IExtendedCSSProperties } = {
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
        props.packagePayload.packageData &&
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
              />
            ),
          )}
        </div>
      ))}
    </div>
  );
};

const ComposedPluginList: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  Radium,
  setDisplayName('PluginList'),
)(PluginList);

export default ComposedPluginList;
