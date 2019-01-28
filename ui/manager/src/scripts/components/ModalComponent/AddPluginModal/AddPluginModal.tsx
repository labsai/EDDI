import * as React from 'react';
import '../ModalComponent.styles.scss';
import { Link, browserHistory } from 'react-router-dom';
import { Component, compose, pure, setDisplayName } from 'recompose';
import Plugin from './Plugin';
import { IBot, IDescriptor, IPackage } from '../../utils/AxiosFunctions';
import { pluginsSelector } from '../../../selectors/PluginSelectors';
import { connect } from 'react-redux';
import * as _ from 'lodash';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import Parser from '../../utils/Parser';
import styles from '../AddPackagesModal/AddPackagesModal.styles';
import ModalActionDispatchers from '../../../actions/ModalActionDispatchers';
import * as renderIf from 'render-if';
import BlueButton from '../../Assets/Buttons/BlueButton';
import WhiteButton from '../../Assets/Buttons/WhiteButton';
import { ClimbingBoxLoader } from 'react-spinners';
import { REGULAR_DICTIONARY } from '../../utils/EddiTypes';
import { DEFAULT_LIMIT } from '../../utils/ApiFunctions';

interface IState {
  selectedPlugins: string[];
  availablePlugins: string[];
  limitedToOneSelect: boolean;
  loading: boolean;
}

interface IPublicProps {
  pluginType: string;
  oldPlugins: string[];
  addPlugins(selectedPlugins: string[]): void;
}

interface IPrivateProps extends IPublicProps {
  error: Error;
  isLoading: boolean;
  plugins: IDescriptor[];
  isAllPluginsLoaded: boolean;
  loadedPlugins: number;
}

class AddPluginModal extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      selectedPlugins: [],
      availablePlugins: [],
      limitedToOneSelect: true,
      loading: false,
    };
  }

  componentDidMount() {
    if (this.props.pluginType === REGULAR_DICTIONARY) {
      this.setState({
        limitedToOneSelect: false,
      });
    }
    if (
      this.props.plugins.length < DEFAULT_LIMIT &&
      !this.props.isAllPluginsLoaded
    ) {
      eddiApiActionDispatchers.fetchPluginsAction(
        this.props.pluginType,
        DEFAULT_LIMIT,
        0,
      );
    }
    this.discardChanges();
  }

  componentWillReceiveProps(nextProps) {
    if (
      !_.isEmpty(
        _.differenceBy(nextProps.plugins, this.props.plugins, 'resource'),
      )
    ) {
      this.discardChanges(nextProps);
    }
  }

  closeModal = () => {
    this.discardChanges();
    ModalActionDispatchers.closeModal();
  };

  selectVersion = (resource: string, version: number) => {
    const id = Parser.getId(resource);
    const availablePlugins = this.state.availablePlugins.map(p => {
      if (Parser.getId(p) === id) {
        return Parser.replaceResourceVersion(p, version);
      }
      return p;
    });
    const selectedPlugins = this.state.selectedPlugins.filter(
      selectedPackage => Parser.getId(selectedPackage) !== id,
    );
    this.setState({
      availablePlugins,
      selectedPlugins,
    });
  };

  selectPlugin = (pluginResource: string) => {
    if (this.state.limitedToOneSelect) {
      if (_.first(this.state.selectedPlugins) === pluginResource) {
        this.setState({
          selectedPlugins: [],
        });
      } else {
        this.setState({
          selectedPlugins: [pluginResource],
        });
      }
    } else {
      if (this.state.selectedPlugins.includes(pluginResource)) {
        this.setState({
          selectedPlugins: this.state.selectedPlugins.filter(
            p => p !== pluginResource,
          ),
        });
      } else {
        this.setState({
          selectedPlugins: this.state.selectedPlugins.concat(pluginResource),
        });
      }
    }
  };

  unsavedChanges(): boolean {
    return !_.isEqual(
      this.state.selectedPlugins.sort(),
      this.props.oldPlugins.sort(),
    );
  }

  discardChanges(props = this.props): void {
    const availablePlugins = props.plugins.map(pkg => {
      return this.getPluginIfUsed(pkg.resource);
    });
    this.setState({
      selectedPlugins: props.oldPlugins,
      availablePlugins,
    });
  }

  isPluginSelected(pluginResource: string): boolean {
    return !!this.state.selectedPlugins.find(
      selectedPlugin =>
        Parser.getId(pluginResource) === Parser.getId(selectedPlugin),
    );
  }

  getPluginIfUsed(pluginResource: string): string {
    const plugin = this.props.oldPlugins.find(
      p => Parser.getId(pluginResource) === Parser.getId(p),
    );
    return plugin || pluginResource;
  }

  selectPlugins = () => {
    this.props.addPlugins(this.state.selectedPlugins);
    this.closeModal();
  };

  createNewPlugin = () => {
    ModalActionDispatchers.showCreateNewConfigModal(
      this.props.pluginType,
      null,
      null,
      null,
      () =>
        ModalActionDispatchers.showAddPluginsModal(
          this.props.pluginType,
          this.props.oldPlugins,
          this.props.addPlugins,
        ),
    );
  };

  loadMore = () => {
    const fetchIndex = Math.floor(this.props.loadedPlugins / DEFAULT_LIMIT);
    if (this.state.loading || _.isEmpty(this.props.plugins)) {
      return;
    }
    this.setState({ loading: true });
    eddiApiActionDispatchers.fetchPluginsAction(
      this.props.pluginType,
      DEFAULT_LIMIT,
      fetchIndex,
    );
  };

  render() {
    return (
      <div>
        <div style={styles.header}>
          <div style={styles.topHeader}>
            <div style={styles.title}>{`Select ${Parser.getPluginName(
              this.props.pluginType,
              true,
            )}`}</div>
            <div style={styles.centerFlex} />
            <WhiteButton
              customStyles={styles.createButton}
              onClick={this.createNewPlugin}
              text={`Create new ${Parser.getPluginName(
                this.props.pluginType,
                false,
              )}`}
            />
            <BlueButton
              customStyles={styles.button}
              disabled={
                !this.unsavedChanges() || _.isEmpty(this.state.selectedPlugins)
              }
              onClick={this.selectPlugins}
              text={`Add ${Parser.getPluginName(this.props.pluginType, false)}`}
            />
          </div>
          <div style={styles.bottomHeader}>
            <div style={styles.centerFlex} />
            <div style={styles.lastModified}>{'Last modified'}</div>
          </div>
        </div>
        <div style={styles.packageList}>
          {renderIf(
            this.props.isAllPluginsLoaded && _.isEmpty(this.props.plugins),
          )(() => (
            <p>
              {'Found no plugins. Create a new ' +
                Parser.getPluginName(this.props.pluginType, false) +
                ' to select one.'}
            </p>
          ))}
          {renderIf(!_.isEmpty(this.state.availablePlugins))(() => (
            <div>
              {this.state.availablePlugins.map((p, i) => (
                <Plugin
                  key={i}
                  selected={this.isPluginSelected(p)}
                  pluginResource={p}
                  handleClick={this.selectPlugin}
                  selectVersion={this.selectVersion}
                />
              ))}
            </div>
          ))}
          {renderIf(this.props.isLoading)(() => (
            <div style={styles.loadingWrapper}>
              <ClimbingBoxLoader loading />
            </div>
          ))}
          {renderIf(
            !this.props.isAllPluginsLoaded &&
              !this.props.isLoading &&
              !this.state.loading,
          )(() => (
            <BlueButton
              customStyles={styles.loadMoreButton}
              onClick={this.loadMore}
              text={'Load More'}
            />
          ))}
        </div>
      </div>
    );
  }
}
const ComposedAddPluginModal: Component<IPrivateProps> = compose<IPrivateProps>(
  pure,
  setDisplayName('AddPluginModal'),
  connect(pluginsSelector),
)(AddPluginModal);

export default ComposedAddPluginModal;
