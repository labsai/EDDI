import * as React from 'react';
import TopBarComponent from '../TopBar/TopBarComponent';
import styles from '../App.style';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { pageEnum } from '../TopBar/NavigationComponent';
import Parser from '../utils/Parser';
import PluginList from '../Plugins/PluginList';
import {
  BEHAVIOR,
  HTTPCALLS,
  OUTPUT,
  REGULAR_DICTIONARY,
} from '../utils/EddiTypes';

interface IRouteProps {
  location: { search: string };
}

function getType(search: string) {
  const queryStrings = Parser.getQueryStrings(search);
  return queryStrings.type;
}

interface IState {
  filterText: string;
}

interface IProps extends IRouteProps {}

const eddiLogo = require('../../../public/images/eddi-logo.png');

class ExtensionsPage extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      filterText: '',
    };
  }

  filter = (text: string) => {
    this.setState({ filterText: text });
  };

  getEddiType() {
    const type = getType(this.props.location.search);
    console.log(type);
    switch (type) {
      case 'regularDictionaries':
        return REGULAR_DICTIONARY;
      case 'behaviorRules':
        return BEHAVIOR;
      case 'outputSets':
        return OUTPUT;
      case 'httpCalls':
        return HTTPCALLS;
      default:
        return;
    }
  }

  render() {
    const type = getType(this.props.location.search);
    return (
      <div>
        <img src={eddiLogo} style={styles.eddiLogo} />
        <div className="content">
          <TopBarComponent page={pageEnum[type]} filter={this.filter} />
          <PluginList pluginType={this.getEddiType()} />
        </div>
      </div>
    );
  }
}

const ComposedExtensionsPage: Component<IPublicProps> = compose<
  IPublicProps,
  IPrivateProps
>(pure, setDisplayName('ExtensionsPage'))(ExtensionsPage);

export default ComposedExtensionsPage;
