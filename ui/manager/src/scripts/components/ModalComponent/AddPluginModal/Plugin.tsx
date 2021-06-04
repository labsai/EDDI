import * as _ from 'lodash';
import * as moment from 'moment';
import * as React from 'react';
import { connect } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import { pluginSelector } from '../../../selectors/PluginSelectors';
import TruncateTextComponent from '../../Assets/TruncateTextComponent';
import VersionSelectComponent from '../../Assets/VersionSelectComponent';
import PackagesUsingPlugin from '../../PackageDetailView/UsedByComponent/PackagesUsingPlugin';
import { IPlugin } from '../../utils/AxiosFunctions';
import styles from '../AddPackagesModal/Package.styles';

interface IPublicProps {
  pluginResource: string;
  selected: boolean;
  handleClick(resource: string): void;
  selectVersion(resource: string, newVersion): void;
}

interface IPrivateProps extends IPublicProps {
  error: Error;
  plugin: IPlugin;
  isLoading: boolean;
}

class Plugin extends React.Component<IPrivateProps> {
  async componentDidMount() {
    if (this.props.pluginResource && _.isUndefined(this.props.plugin)) {
      eddiApiActionDispatchers.fetchPluginAction(this.props.pluginResource);
    }
  }

  async componentDidUpdate(prevProps) {
    if (this.props.pluginResource !== prevProps.pluginResource) {
      eddiApiActionDispatchers.fetchPluginAction(this.props.pluginResource);
    }
  }

  getButtonStyle() {
    if (this.props.selected) {
      return { ...styles.button, backgroundColor: '#4BCA81' };
    } else {
      return {
        ...styles.button,
      };
    }
  }
  getNameStyle() {
    if (this.props.selected) {
      return { ...styles.packageName, color: '#16325C' };
    } else {
      return {
        ...styles.packageName,
      };
    }
  }

  handleClick = () => {
    this.props.handleClick(this.props.plugin.resource);
  };

  selectVersion = (newVersion: number) => {
    this.props.selectVersion(this.props.plugin.resource, newVersion);
  };

  render() {
    return (
      <div>
        {!this.props.plugin && (
          <div>
            {this.props.isLoading && <p>{'Loading plugin'}</p>}
            {!!this.props.error && <p>{'Error: Could not load plugin'}</p>}
            {!this.props.isLoading && !this.props.error && (
              <p>{'This plugin does not exist'}</p>
            )}
          </div>
        )}
        {!!this.props.plugin && (
          <div>
            {!!this.props.error && <p>{'Error: Could not load plugin'}</p>}
            {!this.props.error && _.isEmpty(this.props.plugin) && (
              <p>{'This plugin does not exist'}</p>
            )}
            {!this.props.error && !_.isEmpty(this.props.plugin) && (
              <div style={styles.content}>
                <div style={styles.topContent}>
                  <button
                    onClick={this.handleClick}
                    style={this.getButtonStyle()}>{`${
                    this.props.selected ? '\u2714' : '+'
                  }`}</button>
                  <div style={this.getNameStyle()}>
                    {this.props.plugin.name === ''
                      ? this.props.plugin.id
                      : this.props.plugin.name}
                  </div>
                  <div style={styles.versionSelect}>
                    <VersionSelectComponent
                      currentVersion={this.props.plugin.currentVersion}
                      selectedVersion={this.props.plugin.version}
                      selectVersion={this.selectVersion}
                    />
                  </div>
                  <div style={styles.centerFlex} />
                  <div style={styles.modifiedDate}>
                    {moment(this.props.plugin.lastModifiedOn).format(
                      'DD.MM.YYYY',
                    )}
                  </div>
                </div>
                <div style={styles.bottomContent}>
                  <TruncateTextComponent
                    text={this.props.plugin.description}
                    length={80}
                  />
                  <PackagesUsingPlugin
                    plugin={this.props.plugin}
                    isSmallName={true}
                  />
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    );
  }
}

const ComposedPlugin: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(pluginSelector),
  setDisplayName('Plugin'),
)(Plugin);

export default ComposedPlugin;
