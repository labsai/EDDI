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

const eddiLogo = require('../../../public/images/eddi-logo.png');

interface IRouteProps {
  location: { search: string };
}

interface IState {
  filterText: string;
}

interface IProps extends IRouteProps {}

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

  getTypeFromQueryString() {
    const type = Parser.getQueryStrings(this.props.location.search).type;
    return type;
  }

  getEddiType(type: string) {
    switch (type) {
      case 'dictionary':
        return REGULAR_DICTIONARY;
      case 'behavior':
        return BEHAVIOR;
      case 'output':
        return OUTPUT;
      case 'httpCalls':
        return HTTPCALLS;
      default:
        return;
    }
  }

  render() {
    const type = this.getTypeFromQueryString();
    return (
      <div>
        <img src={eddiLogo} style={styles.eddiLogo} />
        <div>
          <TopBarComponent
            page={pageEnum[type]}
            filter={this.filter}
            type={type}
          />
          <PluginList pluginType={this.getEddiType(type)} />
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
